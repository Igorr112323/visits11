import SwiftUI
import CoreBluetooth
import UIKit
import PhotosUI

// Ключи сохранённых данных на телефоне (те же, что и на Android: token, fullname, device).
private let keyToken  = "token"
private let keyName   = "fullname"
private let keyDevice = "device"

struct ContentView: View {
    @AppStorage(keyToken) private var savedToken: String = ""
    @AppStorage(keyName)  private var savedName: String = ""

    @State private var loginError: String = ""
    @State private var showCamera: Bool = false
    @State private var showPhotoPicker: Bool = false
    @State private var pickedItem: PhotosPickerItem? = nil

    @StateObject private var nfc = NfcReader()

    // Код из QR-кода: если он сохранён — сразу открываем экран отметки.
    private var loggedIn: Bool {
        !savedToken.isEmpty
    }

    var body: some View {
        ZStack {
            Color(red: 0.96, green: 0.97, blue: 0.96).ignoresSafeArea()
            if loggedIn {
                studentScreen
            } else {
                loginScreen
            }
        }
        .onAppear {
            syncBleScanner()
        }
        .onChange(of: savedToken) { _ in
            syncBleScanner()
        }
        .fullScreenCover(isPresented: $showCamera) {
            QrScannerSheet(
                onScanned: { raw in
                    showCamera = false
                    applyQrContent(raw)
                },
                onCancel: {
                    showCamera = false
                }
            )
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickedItem, matching: .images)
        .onChange(of: pickedItem) { newItem in
            guard let item = newItem else { return }
            Task {
                defer { pickedItem = nil }
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = UIImage(data: data),
                   let qr = detectQrInImage(image) {
                    await MainActor.run {
                        applyQrContent(qr)
                    }
                } else {
                    await MainActor.run {
                        loginError = "QR-код на изображении не найден"
                    }
                }
            }
        }
    }

    private func syncBleScanner() {
        if !savedToken.isEmpty {
            let tok = savedToken
            let dev = deviceId()
            nfc.start(token: tok, device: dev)
        } else {
            nfc.stop()
        }
    }

    // MARK: - Вход (только по QR-коду: камера или галерея)

    private var loginScreen: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()

            Text("КубГАУ")
                .font(.system(size: 32, weight: .bold))
                .foregroundColor(Color(red: 0.11, green: 0.37, blue: 0.16))

            Text("Отметка посещаемости")
                .font(.system(size: 15))
                .foregroundColor(Color(red: 0.42, green: 0.49, blue: 0.43))
                .padding(.top, 4)
                .padding(.bottom, 28)

            VStack(alignment: .leading, spacing: 0) {
                Text("Вход только по личному QR-коду.\nПолучите свой QR-код у преподавателя.")
                    .font(.system(size: 14))
                    .foregroundColor(Color(red: 0.29, green: 0.36, blue: 0.30))
                    .padding(.bottom, 18)

                Button(action: {
                    loginError = ""
                    showCamera = true
                }) {
                    Text("Сканировать QR-код камерой")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Color(red: 0.18, green: 0.49, blue: 0.20))
                        .cornerRadius(12)
                }

                Button(action: {
                    loginError = ""
                    showPhotoPicker = true
                }) {
                    Text("Выбрать QR-код из галереи")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(Color(red: 0.18, green: 0.49, blue: 0.20))
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(Color(red: 0.91, green: 0.96, blue: 0.91))
                        .cornerRadius(12)
                }
                .padding(.top, 10)

                if !loginError.isEmpty {
                    Text(loginError)
                        .font(.system(size: 13))
                        .foregroundColor(Color(red: 0.78, green: 0.16, blue: 0.16))
                        .padding(.top, 12)
                }
            }
            .padding(22)
            .background(Color.white)
            .cornerRadius(16)

            Spacer()
        }
        .padding(28)
    }

    private func applyQrContent(_ raw: String) {
        guard let parsed = parseStudentQr(raw) else {
            loginError = "Неверный или повреждённый QR-код"
            return
        }
        loginError = ""
        savedName = parsed.fullName
        savedToken = parsed.token
    }

    // MARK: - Экран студента

    private var studentScreen: some View {
        let displayName = savedName.isEmpty ? "Студент" : savedName

        return VStack(spacing: 0) {
            HStack {
                Text("КубГАУ · Студент")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(Color(red: 0.11, green: 0.37, blue: 0.16))
                Spacer()
                Button("Выйти из аккаунта") {
                    nfc.stop()
                    savedToken = ""
                    savedName = ""
                    loginError = ""
                }
                .font(.system(size: 13))
                .foregroundColor(Color(red: 0.48, green: 0.54, blue: 0.49))
            }

            Spacer()

            VStack(spacing: 8) {
                Text("Авторизован:")
                    .font(.system(size: 13))
                    .foregroundColor(Color(red: 0.48, green: 0.54, blue: 0.49))
                Text(displayName)
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(Color(red: 0.11, green: 0.18, blue: 0.12))
                    .multilineTextAlignment(.center)
            }
            .padding(26)
            .frame(maxWidth: .infinity)
            .background(Color.white)
            .cornerRadius(16)

            Spacer()

            if nfc.marked {
                VStack(spacing: 10) {
                    Text("✓")
                        .font(.system(size: 42, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 76, height: 76)
                        .background(Circle().fill(Color(red: 0.18, green: 0.49, blue: 0.20)))
                    Text(nfc.markMessage)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(Color(red: 0.18, green: 0.49, blue: 0.20))
                        .multilineTextAlignment(.center)
                }
                .padding(.bottom, 20)
            } else {
                Text(nfc.hint)
                    .font(.system(size: 14))
                    .foregroundColor(nfc.hintError
                                     ? Color(red: 0.70, green: 0.29, blue: 0.29)
                                     : Color(red: 0.54, green: 0.60, blue: 0.55))
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 20)
            }
        }
        .padding(24)
    }

    // Уникальный номер этого iPhone (сохраняется при входе, чтобы нельзя было передать чужой QR).
    private func deviceId() -> String {
        let defaults = UserDefaults.standard
        if let saved = defaults.string(forKey: keyDevice), !saved.isEmpty {
            return saved
        }
        let fresh = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        defaults.set(fresh, forKey: keyDevice)
        return fresh
    }
}

// MARK: - Бесконтактная отметка при поднесении вплотную к телефону преподавателя
//
// На бесплатном Apple ID (Sideloadly) Apple блокирует CoreNFC (NFCTagReaderSession),
// поэтому передача «код-из-QR \n id-устройства» на телефон преподавателя идёт по BLE
// со строгим фильтром мощности сигнала (RSSI >= -55 dBm — только физическое поднесение
// телефонов вплотную 1–5 см, из коридора или с парты срабатывание невозможно).

final class NfcReader: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    @Published var marked: Bool = false
    @Published var markMessage: String = "Вы присутствуете"
    @Published var hint: String = "Приложите телефон к телефону преподавателя"
    @Published var hintError: Bool = false

    private let serviceUUID = CBUUID(string: "F0391101-0203-0405-0607-08090A0B0C0D")
    private let charUUID    = CBUUID(string: "F0391102-0203-0405-0607-08090A0B0C0D")

    // Порог уровня сигнала для срабатывания только вплотную (1–5 см от телефона преподавателя).
    private let closeProximityRssiThreshold = -55

    private var central: CBCentralManager?
    private var activePeripheral: CBPeripheral?
    private var activeChar: CBCharacteristic?

    private var token: String = ""
    private var device: String = ""
    private var active: Bool = false
    private var busy: Bool = false
    private var cooldownUntil: Date = .distantPast

    private var writeChunks: [Data] = []
    private var writeIndex: Int = 0
    private var timeoutWork: DispatchWorkItem?
    private var pollTimer: Timer?

    // Коды ответа телефона преподавателя (TeacherCardService).
    private let replyMarked        = 0
    private let replyUnknownQr     = 2
    private let replyUnavailable   = 4
    private let replyDeviceBlocked = 5

    func start(token: String, device: String) {
        self.token = token
        self.device = device
        self.active = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else if central?.state == .poweredOn {
            beginScanIfNeeded()
        }
    }

    func stop() {
        active = false
        cleanupConnection()
        central?.stopScan()
    }

    private func beginScanIfNeeded() {
        guard active, !busy, let central = central, central.state == .poweredOn else { return }
        if !hintError {
            hint = "Приложите телефон к телефону преподавателя"
        }
        central.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            hintError = false
            hint = "Приложите телефон к телефону преподавателя"
            beginScanIfNeeded()
        case .poweredOff:
            hintError = true
            hint = "Включите Bluetooth для бесконтактной отметки"
        case .unauthorized:
            hintError = true
            hint = "Разрешите Bluetooth в Настройках для отметки"
        case .unsupported:
            hintError = true
            hint = "Bluetooth не поддерживается на этом устройстве"
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard active, !busy, !token.isEmpty else { return }
        if Date() < cooldownUntil { return }

        let rssiValue = RSSI.intValue
        // Отсекаем любые сигналы дальше нескольких сантиметров (а также 127 = неизвестный RSSI)
        guard rssiValue < 0 && rssiValue >= closeProximityRssiThreshold else { return }

        busy = true
        central.stopScan()

        activePeripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)

        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.busy else { return }
            self.finishExchange(code: self.replyUnavailable, text: "")
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
              let ch = service.characteristics?.first(where: { $0.uuid == charUUID }) else {
            cleanupConnection()
            beginScanIfNeeded()
            return
        }
        activeChar = ch
        peripheral.setNotifyValue(true, for: ch)

        // Собираем пакет «\x01token\ndevice\x00» и отправляем чанками с подтверждением
        var payload = Data([1])
        payload.append(Data((token + "\n" + device).utf8))
        payload.append(0)

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
            finishExchange(code: replyUnavailable, text: "")
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
        let code = Int(data[0])
        // 0xFF (255) означает, что телефон преподавателя ещё ждёт ответ от ПК
        if code == 0xFF { return }

        let text = data.count > 1
            ? (String(data: data.subdata(in: 1..<data.count), encoding: .utf8) ?? "")
            : ""
        finishExchange(code: code, text: text)
    }

    private func finishExchange(code: Int, text: String) {
        cleanupConnection()

        if code == replyMarked {
            cooldownUntil = Date().addingTimeInterval(5.0)
            marked = true
            hintError = false
            markMessage = text.isEmpty ? "Вы присутствуете" : "Вы присутствуете\n\(text)"
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
        } else {
            cooldownUntil = Date().addingTimeInterval(2.5)
            marked = false
            hintError = true
            switch code {
            case replyUnknownQr:
                hint = "QR-код не найден — возьмите новый у преподавателя"
            case replyDeviceBlocked:
                hint = "Этот телефон привязан к другому студенту"
            default:
                hint = "Нет связи с ПК преподавателя"
            }
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.error)
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
