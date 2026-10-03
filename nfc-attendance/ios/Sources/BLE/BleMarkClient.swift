import Combine
import CoreBluetooth
import Foundation

/// Касание по Bluetooth: iPhone прикладывают к телефону преподавателя,
/// приложение находит BLE-метку терминала, проверяет, что сигнал сильный
/// (телефон действительно рядом), и отправляет отметку.
///
/// Это «NFC, которого нет»: на iPhone нет HCE, зато есть BLE — для студента
/// разницы нет, он просто прикладывает телефон.
///
/// Порядок работы:
///   1. сканируем эфир по сервису терминала (A110) вместе с Service Data —
///      в ней лежит токен, который терминал меняет каждые 5 секунд;
///   2. смотрим на RSSI: если телефон далеко (коридор), отметка не отправляется;
///   3. подключаемся, читаем данные пары (A111), уточняем RSSI по соединению;
///   4. пишем отметку (A112) вместе с токеном и измеренным RSSI;
///   5. получаем подтверждение (A113) и показываем «Вы отмечены».
final class BleMarkClient: NSObject, ObservableObject {

    enum State: Equatable {
        case idle
        case unavailable(String)
        case searching
        case connecting
        case tooFar(Int)
        case sending
        case accepted(String)
        case rejected(String)
    }

    /// Данные пары, прочитанные с терминала.
    struct TerminalSession: Equatable {
        let sessionId: String
        let subject: String
        let teacherId: String
        let minRssi: Int
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var lastRssi: Int?
    @Published private(set) var statusText: String = "Приложите iPhone к телефону преподавателя"

    /// Успешное касание: (пара, измеренный RSSI, время).
    var onMark: ((TerminalSession, Int, Date) -> Void)?

    // ------------------------------------------------------------------ UUID

    private let serviceUUID = CBUUID(string: "0000A110-0000-1000-8000-00805F9B34FB")
    private let infoUUID = CBUUID(string: "0000A111-0000-1000-8000-00805F9B34FB")
    private let markUUID = CBUUID(string: "0000A112-0000-1000-8000-00805F9B34FB")
    private let ackUUID = CBUUID(string: "0000A113-0000-1000-8000-00805F9B34FB")

    /// Ниже этого RSSI даже не пытаемся подключаться.
    private let connectFloor = -80
    /// Сколько ждём, пока телефон приложат (секунд).
    private let waitTimeout: TimeInterval = 90
    /// Сколько ждём ответа терминала после отправки.
    private let ackTimeout: TimeInterval = 5
    /// Не чаще одного чтения RSSI в это время.
    private let rssiPollInterval: TimeInterval = 0.4

    // --------------------------------------------------------------- состояние

    private var central: CBCentralManager?
    private var target: CBPeripheral?
    private var token: Data?
    private var session: TerminalSession?
    private var markCharacteristic: CBCharacteristic?
    private var ackCharacteristic: CBCharacteristic?

    private var deviceId: String = ""
    private var studentName: String = ""

    /// Куски отметки, которые ещё не отправлены (запись идёт по одному пакету:
    /// CoreBluetooth не разрешает начать следующую запись, пока не пришёл ответ).
    private var pendingChunks: [Data] = []

    private var rssiSamples: [Int] = []
    private var rssiTimer: Timer?
    private var timeoutWork: DispatchWorkItem?
    private var finished = false

    // ------------------------------------------------------------------ запуск

    /// Начать искать терминал. Вызывается, когда студент открыл экран отметки.
    func start(deviceId: String, studentName: String) {
        self.deviceId = deviceId
        self.studentName = studentName
        reset()

        if central == nil {
            central = CBCentralManager(delegate: self, queue: nil)
        } else {
            beginScanIfReady()
        }

        timeoutWork = DispatchWorkItem { [weak self] in
            guard let self, !self.finished else { return }
            self.finish(state: .rejected("Терминал не найден. Проверьте, что пара начата, "
                                         + "и поднесите телефон ближе."))
        }
        if let work = timeoutWork {
            DispatchQueue.main.asyncAfter(deadline: .now() + waitTimeout, execute: work)
        }
    }

    /// Остановить всё (ушли с экрана, отметка получена, нажали «Отмена»).
    func stop() {
        finish(state: .idle, silent: true)
    }

    // ------------------------------------------------------------------ утилиты

    private func reset() {
        finished = false
        session = nil
        token = nil
        markCharacteristic = nil
        ackCharacteristic = nil
        rssiSamples = []
        lastRssi = nil
        rssiTimer?.invalidate()
        rssiTimer = nil
        state = .searching
        statusText = "Приложите iPhone к телефону преподавателя"
    }

    private func finish(state newState: State, silent: Bool = false) {
        if !silent { finished = true }
        timeoutWork?.cancel()
        timeoutWork = nil
        rssiTimer?.invalidate()
        rssiTimer = nil

        central?.stopScan()
        if let target {
            central?.cancelPeripheralConnection(target)
        }
        target = nil
        DispatchQueue.main.async {
            self.state = newState
        }
    }

    private func beginScanIfReady() {
        guard let central, central.state == .poweredOn, !finished else { return }
        central.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
    }

    private func tokenHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private func isoTime(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone.current
        return formatter.string(from: date)
    }
}

// ------------------------------------------------------------ CBCentralManagerDelegate

extension BleMarkClient: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            beginScanIfReady()
        case .poweredOff:
            finish(state: .unavailable("Bluetooth выключен — включите его в Настройках iPhone"))
        case .unauthorized:
            finish(state: .unavailable("Приложению не разрешён Bluetooth. Разрешите в Настройках iPhone."))
        case .unsupported:
            finish(state: .unavailable("Этот iPhone не поддерживает Bluetooth LE"))
        default:
            break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard !finished, target == nil else { return }

        let rssi = RSSI.intValue
        guard rssi < 0, rssi >= connectFloor else { return }  // слишком далеко — не подключаемся

        // Токен приходит в Service Data: он доказывает, что мы слышали свежую метку
        let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data]
        guard let data = serviceData?[serviceUUID], data.count >= 8 else { return }

        token = data
        lastRssi = rssi
        target = peripheral
        peripheral.delegate = self
        state = .connecting
        statusText = "Терминал найден (сигнал \(rssi) dBm) — проверяем расстояние…"
        central.stopScan()
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        target = nil
        rssiSamples = []
        state = .searching
        statusText = "Не удалось подключиться, пробуем ещё раз…"
        beginScanIfReady()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard !finished else { return }
        // Связь оборвалась до отметки: пробуем заново
        target = nil
        rssiSamples = []
        if state == .sending || state == .connecting {
            state = .searching
            statusText = "Связь прервалась, поднесите телефон ещё раз"
            beginScanIfReady()
        }
    }
}

// ------------------------------------------------------------- CBPeripheralDelegate

extension BleMarkClient: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            finish(state: .rejected("Терминал не отдал данные пары"))
            return
        }
        peripheral.discoverCharacteristics([infoUUID, markUUID, ackUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            finish(state: .rejected("Ошибка чтения данных терминала"))
            return
        }
        guard let characteristics = service.characteristics else { return }

        markCharacteristic = characteristics.first { $0.uuid == markUUID }
        ackCharacteristic = characteristics.first { $0.uuid == ackUUID }

        if let info = characteristics.first(where: { $0.uuid == infoUUID }) {
            statusText = "Читаем данные пары…"
            peripheral.readValue(for: info)
        } else {
            finish(state: .rejected("Терминал не сообщил данные пары"))
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }

        switch characteristic.uuid {
        case infoUUID:
            handleInfo(value, peripheral: peripheral)

        case ackUUID:
            handleAck(value)

        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == ackUUID else { return }
        if error != nil {
            finish(state: .rejected("Терминал не подтверждает связь"))
            return
        }
        if characteristic.isNotifying {
            sendMark(peripheral)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard error == nil else { return }
        let rssi = RSSI.intValue
        lastRssi = rssi
        rssiSamples.append(rssi)
        if rssiSamples.count > 2 {
            rssiSamples.removeFirst()
        }

        let average = rssiSamples.reduce(0, +) / max(rssiSamples.count, 1)
        let required = session?.minRssi ?? -60

        if average >= required {
            rssiTimer?.invalidate()
            rssiTimer = nil
            statusText = "Сигнал хороший (\(average) dBm) — отправляем отметку…"
            prepareAndSend(peripheral)
        } else {
            state = .tooFar(average)
            statusText = "Поднесите iPhone ближе к телефону преподавателя (сигнал \(average) dBm)"
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == markUUID else { return }

        if error != nil {
            pendingChunks.removeAll()
            finish(state: .rejected("Не удалось отправить отметку, попробуйте ещё раз"))
            return
        }

        // Очередной кусок принят терминалом — отправляем следующий
        writeNextChunk(peripheral, characteristic: characteristic)
    }

    private func writeNextChunk(_ peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        guard !pendingChunks.isEmpty else { return }   // всё ушло, ждём ACK
        let chunk = pendingChunks.removeFirst()
        peripheral.writeValue(chunk, for: characteristic, type: .withResponse)
    }

    // ---------------------------------------------------------------- шаги

    private func handleInfo(_ value: Data, peripheral: CBPeripheral) {
        guard let json = try? JSONSerialization.jsonObject(with: value) as? [String: Any],
              let sessionId = json["session_id"] as? String, !sessionId.isEmpty else {
            finish(state: .rejected("Терминал не сообщил данные пары"))
            return
        }

        let info = TerminalSession(
            sessionId: sessionId,
            subject: json["subject"] as? String ?? "",
            teacherId: json["teacher_id"] as? String ?? "",
            minRssi: json["min_rssi"] as? Int ?? -60
        )
        session = info

        // Уточняем расстояние: несколько чтений RSSI по живому соединению
        state = .connecting
        statusText = "Проверяем расстояние…"
        peripheral.readRSSI()
        rssiTimer?.invalidate()
        rssiTimer = Timer.scheduledTimer(withTimeInterval: rssiPollInterval, repeats: true) { [weak self] timer in
            guard let self, !self.finished else { timer.invalidate(); return }
            if self.state == .sending { timer.invalidate(); return }
            peripheral.readRSSI()
        }
    }

    private func prepareAndSend(_ peripheral: CBPeripheral) {
        guard let ack = ackCharacteristic else {
            finish(state: .rejected("Терминал не готов принимать отметку"))
            return
        }
        peripheral.setNotifyValue(true, for: ack)
    }

    private func sendMark(_ peripheral: CBPeripheral) {
        guard let mark = markCharacteristic, let token, session != nil else {
            finish(state: .rejected("Терминал не готов принимать отметку"))
            return
        }

        let rssi = rssiSamples.reduce(0, +) / max(rssiSamples.count, 1)
        let payload: [String: Any] = [
            "device": deviceId,
            "name": studentName,
            "token": tokenHex(token),
            "time": isoTime(Date()),
            "rssi": rssi,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else {
            finish(state: .rejected("Внутренняя ошибка приложения"))
            return
        }

        state = .sending
        statusText = "Отправляем отметку…"

        // Отметка небольшая, но режем на куски по 180 байт (первый байт — флаг:
        // 0x01 «продолжение», 0x00 «конец») и отправляем их по очереди, ожидая ответ.
        let chunkSize = 180
        pendingChunks = []
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            var chunk = Data([end < data.count ? 0x01 : 0x00])
            chunk.append(data.subdata(in: offset..<end))
            pendingChunks.append(chunk)
            offset = end
        }
        writeNextChunk(peripheral, characteristic: mark)

        // Если терминал промолчит — не висим вечно
        DispatchQueue.main.asyncAfter(deadline: .now() + ackTimeout) { [weak self] in
            guard let self, !self.finished, self.state == .sending else { return }
            self.finish(state: .rejected("Терминал не ответил. Попробуйте приложить телефон ещё раз."))
        }
    }

    private func handleAck(_ value: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: value) as? [String: Any] else { return }
        let code = json["code"] as? Int ?? -1
        let message = json["message"] as? String ?? ""

        // «0» — принято; всё остальное — отказ терминала
        guard code == 0 else {
            finish(state: .rejected(message.isEmpty ? "Терминал отклонил отметку" : message))
            return
        }

        guard let session else {
            finish(state: .rejected("Потеряны данные пары"))
            return
        }

        let rssi = rssiSamples.reduce(0, +) / max(rssiSamples.count, 1)
        let time = Date()
        finished = true
        finish(state: .accepted(session.subject), silent: true)
        onMark?(session, rssi, time)
    }
}
