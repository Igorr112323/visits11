import SwiftUI
import CoreBluetooth
import UIKit

// MARK: - константы и цвета (1-в-1 с Android Student res/values/colors.xml)

private let prefs = UserDefaults.standard
private let keyToken = "token"   // код студента из QR-кода — заменяет логин и пароль
private let keyName = "name"     // ФИО студента (показываем на экране)
private let keyDevice = "device" // id устройства: аккаунт привязывается к телефону

private let colorBg      = Color(red: 0x0A / 255.0, green: 0x0D / 255.0, blue: 0x1A / 255.0)
private let colorPanel   = Color(red: 0x14 / 255.0, green: 0x18 / 255.0, blue: 0x27 / 255.0)
private let colorPanel2  = Color(red: 0x1C / 255.0, green: 0x21 / 255.0, blue: 0x36 / 255.0)
private let colorBorder  = Color(red: 0x2A / 255.0, green: 0x31 / 255.0, blue: 0x50 / 255.0)
private let colorText    = Color(red: 0xF0 / 255.0, green: 0xF2 / 255.0, blue: 0xFA / 255.0)
private let colorTextDim = Color(red: 0x90 / 255.0, green: 0x99 / 255.0, blue: 0xB8 / 255.0)
private let colorAccent  = Color(red: 0x63 / 255.0, green: 0x66 / 255.0, blue: 0xF1 / 255.0)

/// Уникальный ID устройства — аккаунт привязывается к первому телефону.
private func deviceId() -> String {
    if let saved = prefs.string(forKey: keyDevice), !saved.isEmpty {
        return saved
    }
    let id = UUID().uuidString
    prefs.set(id, forKey: keyDevice)
    return id
}

// MARK: - модель бесконтактной отметки (только касание вплотную 0–2 см)
//
// На телефоне преподавателя выставлена минимальная аппаратная мощность
// передатчика (ADVERTISE_TX_POWER_ULTRA_LOW), а на iPhone — жёсткий порог
// сигнала RSSI >= -46 dBm при активном экране: срабатывает только при
// физическом прикладывании корпуса iPhone к телефону преподавателя (0–2 см),
// из рюкзака или кармана сигнал не проходит.

final class MarkModel: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    /// Пустая строка = показываем стандартное «ФИО \n Приложите телефон к телефону преподавателя».
    /// После касания здесь появляется «Вы отмечены — ФИО» или текст ошибки.
    @Published var statusOverride = ""

    /// QR-код не найден в журнале — нужно войти заново.
    @Published var needsLogin = false

    private let serviceUUID = CBUUID(string: "F0391101-0203-0405-0607-08090A0B0C0D")
    private let charUUID    = CBUUID(string: "F0391102-0203-0405-0607-08090A0B0C0D")

    // Жёсткий порог мощности сигнала при минимальной мощности передатчика (ULTRA_LOW):
    // достигается только при касании телефонов вплотную (0–2 см).
    private let touchOnlyRssiThreshold = -46

    private var central: CBCentralManager?
    private var activePeripheral: CBPeripheral?
    private var activeChar: CBCharacteristic?

    private var scanningWanted = false
    private var busy = false
    private var lastMarkedAt: Date = .distantPast
    private var cooldownUntil: Date = .distantPast

    private var writeChunks: [Data] = []
    private var writeIndex = 0
    private var timeoutWork: DispatchWorkItem?
    private var pollTimer: Timer?

    func startAuto() {
        guard let token = prefs.string(forKey: keyToken), !token.isEmpty else {
            return
        }
        scanningWanted = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else {
            beginScanIfNeeded()
        }
    }

    func stop() {
        scanningWanted = false
        cleanupConnection()
        central?.stopScan()
    }

    private func beginScanIfNeeded() {
        guard scanningWanted, !busy, let central = central, central.state == .poweredOn else {
            return
        }
        central.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            if statusOverride.contains("Bluetooth") {
                statusOverride = ""
            }
            beginScanIfNeeded()
        case .poweredOff:
            statusOverride = "Включите Bluetooth в настройках телефона"
        case .unauthorized:
            statusOverride = "Разрешите Bluetooth в настройках телефона"
        case .unsupported:
            statusOverride = "Bluetooth на этом телефоне недоступен"
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard scanningWanted, !busy else { return }
        guard UIApplication.shared.applicationState == .active else { return }
        guard let token = prefs.string(forKey: keyToken), !token.isEmpty else { return }
        if Date() < cooldownUntil { return }

        let rssiValue = RSSI.intValue
        // Срабатываем только при физическом касании телефонов вплотную (0–2 см)
        guard rssiValue < 0 && rssiValue >= touchOnlyRssiThreshold else { return }

        busy = true
        central.stopScan()

        activePeripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)

        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.busy else { return }
            self.finishExchange(code: 4, value: "")
        }
        timeoutWork?.cancel()
        timeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0, execute: work)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        cleanupConnection()
        beginScanIfNeeded()
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        if busy {
            cleanupConnection()
            beginScanIfNeeded()
        }
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            cleanupConnection()
            beginScanIfNeeded()
            return
        }
        peripheral.discoverCharacteristics([charUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard error == nil,
              let ch = service.characteristics?.first(where: { $0.uuid == charUUID }),
              let token = prefs.string(forKey: keyToken), !token.isEmpty else {
            cleanupConnection()
            beginScanIfNeeded()
            return
        }
        activeChar = ch
        peripheral.setNotifyValue(true, for: ch)

        // Пакет: [0x01] + UTF8(token + "\n" + deviceId) + [0x00]
        var payload = Data([UInt8(1)])
        payload.append(Data((token + "\n" + deviceId()).utf8))
        payload.append(UInt8(0))

        let maxLen = max(20, min(180, peripheral.maximumWriteValueLength(for: .withResponse)))
        var chunks: [Data] = []
        var offset = 0
        while offset < payload.count {
            let end = min(offset + maxLen, payload.count)
            chunks.append(payload.subdata(in: offset..<end))
            offset = end
        }
        writeChunks = chunks
        writeIndex = 0
        sendNextChunk(to: peripheral, characteristic: ch)
    }

    private func sendNextChunk(to peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        guard writeIndex < writeChunks.count else {
            startReadPolling(peripheral: peripheral, characteristic: characteristic)
            return
        }
        let chunk = writeChunks[writeIndex]
        writeIndex += 1
        peripheral.writeValue(chunk, for: characteristic, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        if error != nil {
            finishExchange(code: 4, value: "")
            return
        }
        sendNextChunk(to: peripheral, characteristic: characteristic)
    }

    private func startReadPolling(peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { [weak self] _ in
            guard let self = self, self.busy else { return }
            peripheral.readValue(for: characteristic)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard error == nil, let data = characteristic.value, !data.isEmpty else { return }
        let result = data[data.startIndex]
        // 0xFF — телефон преподавателя ещё ждёт ответ от ПК по USB
        if result == 0xFF { return }

        let value = String(data: data.dropFirst(), encoding: .utf8) ?? ""
        finishExchange(code: Int(result), value: value)
    }

    private func finishExchange(code: Int, value: String) {
        cleanupConnection()

        switch code {
        case 0:
            lastMarkedAt = Date()
            cooldownUntil = Date().addingTimeInterval(5.0)
            statusOverride = value.isEmpty ? "Вы отмечены" : "Вы отмечены — \(value)"
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case 2:
            cooldownUntil = Date().addingTimeInterval(2.5)
            prefs.removeObject(forKey: keyToken)
            prefs.removeObject(forKey: keyName)
            needsLogin = true
            statusOverride = ""
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        case 5:
            cooldownUntil = Date().addingTimeInterval(2.5)
            statusOverride = "Этот телефон привязан к другому студенту"
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        default:
            cooldownUntil = Date().addingTimeInterval(2.0)
            // Как на Android: если только что успешно отметились, не затираем «Вы отмечены» при отведении руки
            if Date().timeIntervalSince(lastMarkedAt) >= 5.0 {
                statusOverride = "Не получилось: перекличка не идёт"
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }

        let delay = max(0.5, cooldownUntil.timeIntervalSinceNow)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.beginScanIfNeeded()
        }
    }

    private func cleanupConnection() {
        timeoutWork?.cancel()
        timeoutWork = nil
        pollTimer?.invalidate()
        pollTimer = nil
        writeChunks = []
        writeIndex = 0
        if let p = activePeripheral {
            central?.cancelPeripheralConnection(p)
        }
        activePeripheral = nil
        activeChar = nil
        busy = false
    }
}

// MARK: - интерфейс (точная копия Android Student activity_main.xml)

struct ContentView: View {
    @StateObject private var nfc = MarkModel()
    @AppStorage(keyToken) private var savedToken = ""
    @AppStorage(keyName) private var studentName = ""

    private var loggedIn: Bool {
        !savedToken.isEmpty
    }

    private var defaultPrompt: String {
        studentName.isEmpty
            ? "Приложите телефон к телефону преподавателя"
            : "\(studentName)\nПриложите телефон к телефону преподавателя"
    }

    var body: some View {
        ZStack {
            colorBg.ignoresSafeArea()

            // Логотип КубГАУ сверху по центру (110x110, отступ сверху 40 — как в activity_main.xml)
            VStack {
                if let uiLogo = UIImage(named: "logo") ?? UIImage(contentsOfFile: Bundle.main.path(forResource: "logo", ofType: "png") ?? "") {
                    Image(uiImage: uiLogo)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 110, height: 110)
                        .padding(.top, 40)
                }
                Spacer()
            }

            // По центру: либо карточка входа по QR-коду, либо текст статуса (без лишних кнопок)
            if !loggedIn || nfc.needsLogin {
                LoginView(done: {
                    nfc.needsLogin = false
                    nfc.statusOverride = ""
                    nfc.startAuto()
                })
                .onAppear {
                    nfc.stop()
                }
            } else {
                Text(nfc.statusOverride.isEmpty ? defaultPrompt : nfc.statusOverride)
                    .font(.system(size: 15))
                    .foregroundColor(colorTextDim)
                    .multilineTextAlignment(.center)
                    .padding(32)
                    .contentShape(Rectangle())
                    // Как на Android: долгое нажатие на текст статуса сбрасывает студента для входа по новому QR
                    .onLongPressGesture(minimumDuration: 0.7) {
                        nfc.stop()
                        nfc.statusOverride = ""
                        savedToken = ""
                        studentName = ""
                        UINotificationFeedbackGenerator().notificationOccurred(.warning)
                    }
                    .onAppear {
                        UIApplication.shared.isIdleTimerDisabled = true
                        nfc.startAuto()
                    }
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// Карточка входа по QR-коду — точная копия entryPanel в Android Student.
struct LoginView: View {
    var done: () -> Void

    @State private var showQrPicker = false
    @State private var showCamera = false
    @State private var qrMessage: String?

    var body: some View {
        VStack(spacing: 0) {
            Text("Вход по QR-коду студента")
                .font(.system(size: 16))
                .foregroundColor(colorText)
                .multilineTextAlignment(.center)
                .frame(width: 260)
                .padding(.bottom, 16)

            Text(qrMessage ?? "Свой код можно отсканировать камерой или сохранить картинкой и выбрать её из галереи")
                .font(.system(size: 13))
                .foregroundColor(colorTextDim)
                .multilineTextAlignment(.center)
                .frame(width: 260)
                .padding(.bottom, 18)

            Button {
                qrMessage = nil
                showCamera = true
            } label: {
                Text("Сканировать QR-код")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.white)
                    .frame(width: 260, height: 48)
                    .background(colorAccent)
                    .cornerRadius(12)
            }

            Button {
                qrMessage = nil
                showQrPicker = true
            } label: {
                Text("Загрузить из галереи")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(colorText)
                    .frame(width: 260, height: 48)
                    .background(colorPanel2)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(colorBorder, lineWidth: 1)
                    )
                    .cornerRadius(12)
            }
            .padding(.top, 10)
        }
        .padding(.horizontal, 24)
        .padding(.top, 26)
        .padding(.bottom, 24)
        .background(colorPanel)
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(colorBorder, lineWidth: 1)
        )
        .cornerRadius(20)
        .sheet(isPresented: $showQrPicker) {
            PhotoPicker(onPick: importImage, onFinish: { showQrPicker = false })
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraScanner(onCode: importCode, onCancel: { showCamera = false })
        }
    }

    /// Код, распознанный камерой.
    private func importCode(_ text: String) {
        showCamera = false
        DispatchQueue.main.async {
            apply(text)
        }
    }

    /// Картинка из галереи: распознаём в фоне, чтобы не подвешивать интерфейс.
    private func importImage(_ image: UIImage) {
        qrMessage = "Читаю QR-код…"
        DispatchQueue.global(qos: .userInitiated).async {
            let text = QrImageDecoder.text(from: image)
            DispatchQueue.main.async { apply(text) }
        }
    }

    /// Общий шаг для обоих способов: сохраняем код студента — это и есть вход.
    private func apply(_ text: String?) {
        guard let text, let credentials = StudentQrCredentials.parse(text) else {
            qrMessage = "Это не QR-код студента"
            return
        }
        prefs.set(credentials.token, forKey: keyToken)
        prefs.set(credentials.name, forKey: keyName)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        done()
    }
}
