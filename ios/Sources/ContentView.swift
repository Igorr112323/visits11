import SwiftUI
import CoreBluetooth
import UIKit

// MARK: - константы

private let prefs = UserDefaults.standard
private let keyToken = "token"   // код студента из QR-кода — заменяет логин и пароль
private let keyName = "name"     // ФИО студента (показываем на экране)
private let keyDevice = "device" // id устройства: аккаунт привязывается к телефону

private let green = Color(red: 0.29, green: 0.78, blue: 0.42)

/// Уникальный ID устройства — аккаунт привязывается к первому телефону.
private func deviceId() -> String {
    if let saved = prefs.string(forKey: keyDevice), !saved.isEmpty {
        return saved
    }
    let id = UUID().uuidString
    prefs.set(id, forKey: keyDevice)
    return id
}

// MARK: - модель бесконтактной отметки (вплотную к телефону преподавателя)
//
// На бесплатном Apple ID (Sideloadly) Apple блокирует CoreNFC (NFCTagReaderSession),
// поэтому передача «код-из-QR \n id-устройства» на телефон преподавателя идёт по BLE
// со строгим фильтром мощности сигнала (RSSI >= -55 dBm — только физическое поднесение
// телефонов вплотную 1–5 см; из коридора или с парты срабатывание невозможно).

final class MarkModel: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    /// Результат последнего касания («Вы отмечены — Иванов И. И.»).
    @Published var status = "Приложите iPhone вплотную к телефону преподавателя"

    /// QR-код не найден в журнале — нужно войти заново.
    @Published var needsLogin = false

    private let serviceUUID = CBUUID(string: "F0391101-0203-0405-0607-08090A0B0C0D")
    private let charUUID    = CBUUID(string: "F0391102-0203-0405-0607-08090A0B0C0D")

    // Порог мощности сигнала для срабатывания только вплотную (1–5 см от телефона преподавателя).
    private let closeProximityRssiThreshold = -55

    private var central: CBCentralManager?
    private var activePeripheral: CBPeripheral?
    private var activeChar: CBCharacteristic?

    private var scanningWanted = false
    private var busy = false
    private var cooldownUntil: Date = .distantPast

    private var writeChunks: [Data] = []
    private var writeIndex = 0
    private var timeoutWork: DispatchWorkItem?
    private var pollTimer: Timer?

    /// Запускает автоматическое ожидание поднесения к телефону преподавателя (как на Android).
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

    /// Ручное нажатие на кнопку «Отметиться» — сбрасывает паузу и ждёт поднесения вплотную.
    func start() {
        guard let token = prefs.string(forKey: keyToken), !token.isEmpty else {
            needsLogin = true
            return
        }
        cooldownUntil = .distantPast
        scanningWanted = true
        if central == nil {
            status = "Приложите iPhone вплотную к телефону преподавателя"
            central = CBCentralManager(delegate: self, queue: .main)
            return
        }
        switch central?.state {
        case .poweredOn:
            status = "Приложите iPhone вплотную к телефону преподавателя"
            beginScanIfNeeded()
        case .poweredOff:
            status = "Включите Bluetooth в настройках iPhone"
        case .unauthorized:
            status = "Разрешите Bluetooth для приложения в Настройках"
        case .unsupported:
            status = "Bluetooth недоступен на этом iPhone"
        default:
            status = "Приложите iPhone вплотную к телефону преподавателя"
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
            if status.isEmpty || status.contains("Bluetooth") {
                status = "Приложите iPhone вплотную к телефону преподавателя"
            }
            beginScanIfNeeded()
        case .poweredOff:
            status = "Включите Bluetooth в настройках iPhone"
        case .unauthorized:
            status = "Разрешите Bluetooth для приложения в Настройках"
        case .unsupported:
            status = "Bluetooth недоступен на этом iPhone"
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard scanningWanted, !busy else { return }
        guard let token = prefs.string(forKey: keyToken), !token.isEmpty else { return }
        if Date() < cooldownUntil { return }

        let rssiValue = RSSI.intValue
        // Отсекаем любые сигналы дальше нескольких сантиметров (а также 127 = неизвестный RSSI)
        guard rssiValue < 0 && rssiValue >= closeProximityRssiThreshold else { return }

        busy = true
        central.stopScan()
        status = "Отмечаем…"

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
            cooldownUntil = Date().addingTimeInterval(5.0)
            status = value.isEmpty ? "Вы отмечены" : "Вы отмечены — \(value)"
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case 2:
            cooldownUntil = Date().addingTimeInterval(2.5)
            prefs.removeObject(forKey: keyToken)
            needsLogin = true
            status = "Такого QR-кода нет в журнале — войдите заново"
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        case 5:
            cooldownUntil = Date().addingTimeInterval(2.5)
            status = "Этот телефон привязан к другому студенту"
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        default:
            cooldownUntil = Date().addingTimeInterval(2.5)
            status = "Не получилось: перекличка не идёт"
            UINotificationFeedbackGenerator().notificationOccurred(.error)
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

// MARK: - интерфейс

struct ContentView: View {
    @StateObject private var nfc = MarkModel()
    @State private var showLogin = false
    @AppStorage(keyToken) private var savedToken = ""
    @AppStorage(keyName) private var studentName = ""

    private var loggedIn: Bool {
        !savedToken.isEmpty
    }

    var body: some View {
        VStack(spacing: 18) {
            if showLogin || !loggedIn || nfc.needsLogin {
                LoginView(done: {
                    showLogin = false
                    nfc.needsLogin = false
                    nfc.status = "Приложите iPhone вплотную к телефону преподавателя"
                    nfc.startAuto()
                })
                .onAppear {
                    nfc.stop()
                }
            } else {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 84))
                    .foregroundColor(green)
                Text("Visits11")
                    .font(.title.bold())
                if !studentName.isEmpty {
                    Text(studentName)
                        .font(.headline)
                }
                Text(nfc.status.isEmpty ? "Приложите iPhone вплотную к телефону преподавателя" : nfc.status)
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                    .animation(.easeInOut, value: nfc.status)

                Button {
                    nfc.start()
                } label: {
                    Text("Отметиться")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .tint(green)
                .padding(.top, 8)

                Button("Сменить студента") {
                    nfc.stop()
                    showLogin = true
                }
                .font(.footnote)
                .onAppear {
                    nfc.startAuto()
                }
            }
        }
        .padding(24)
    }
}

/// Вход: логина и пароля нет — только QR-код (камерой или из галереи).
struct LoginView: View {
    var done: () -> Void

    @State private var showQrPicker = false
    @State private var showCamera = false
    @State private var qrMessage: String?

    var body: some View {
        VStack(spacing: 14) {
            Text("Вход")
                .font(.title.bold())
            Text("Отсканируйте свой QR-код или выберите картинку из галереи")
                .font(.footnote)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            Button {
                qrMessage = nil
                showCamera = true
            } label: {
                Text("Сканировать QR-код")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
            .tint(green)
            .padding(.top, 8)

            Button {
                qrMessage = nil
                showQrPicker = true
            } label: {
                Text("Загрузить из галереи")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)

            if let qrMessage {
                Text(qrMessage)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
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
            qrMessage = "В этой картинке нет кода для входа"
            return
        }
        prefs.set(credentials.token, forKey: keyToken)
        prefs.set(credentials.name, forKey: keyName)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        done()
    }
}
