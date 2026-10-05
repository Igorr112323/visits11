import Foundation
import SwiftUI
import AVFoundation
import CoreBluetooth
import Combine
import UIKit

private let tapServiceId = CBUUID(string: "F0391101-0203-4000-8000-00805F9B34FB")
private let identityCharacteristicId = CBUUID(string: "F0391102-0203-4000-8000-00805F9B34FB")
private let markCharacteristicId = CBUUID(string: "F0391103-0203-4000-8000-00805F9B34FB")

struct StudentQrPayload: Codable {
    let version: Int
    let studentKey: String
    let fullName: String
    let serverUrl: String
}

struct MarkRecord: Codable, Identifiable, Equatable {
    let id: String
    let studentKey: String
    let deviceId: String
    let occurredAt: String
}

final class StudentPeripheral: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    @Published private(set) var bluetoothStatus = "Запуск Bluetooth"
    var onMark: (() -> Void)?
    private var manager: CBPeripheralManager!
    private var identityCharacteristic: CBMutableCharacteristic?
    private var markCharacteristic: CBMutableCharacteristic?
    private var identityData = Data()
    private var didAddService = false

    override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: nil, options: [CBPeripheralManagerOptionRestoreIdentifierKey: "ru.visits11.student.peripheral"])
    }

    func setIdentity(studentKey: String, fullName: String, deviceId: String) {
        let value: [String: String] = ["studentKey": studentKey, "fullName": fullName, "deviceId": deviceId]
        guard let data = try? JSONSerialization.data(withJSONObject: value) else { return }
        identityData = data
        // The characteristic is dynamic (value=nil), so reads always use this
        // latest payload instead of a value CoreBluetooth cached at addService time.
        if manager.state == .poweredOn && !didAddService {
            installService()
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        DispatchQueue.main.async {
            switch peripheral.state {
            case .poweredOn:
                self.bluetoothStatus = "Bluetooth готов"
                self.installService()
            case .poweredOff:
                self.didAddService = false
                self.identityCharacteristic = nil
                self.markCharacteristic = nil
                self.bluetoothStatus = "Включите Bluetooth"
            case .unauthorized:
                self.didAddService = false
                self.identityCharacteristic = nil
                self.markCharacteristic = nil
                self.bluetoothStatus = "Разрешите Bluetooth в настройках iPhone"
            case .unsupported:
                self.bluetoothStatus = "Этот iPhone не поддерживает Bluetooth LE"
            default:
                self.bluetoothStatus = "Ожидание Bluetooth"
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        DispatchQueue.main.async {
            if let error {
                self.bluetoothStatus = "Не удалось добавить BLE-службу: \(error.localizedDescription)"
                self.didAddService = false
                return
            }
            self.didAddService = true
            self.bluetoothStatus = "Запускаю Bluetooth-связь"
            peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [tapServiceId]])
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        DispatchQueue.main.async {
            if let error {
                self.bluetoothStatus = "Не удалось включить BLE: \(error.localizedDescription)"
            } else {
                self.bluetoothStatus = "Готов к касанию"
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == identityCharacteristicId else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        guard request.offset <= identityData.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = identityData.subdata(in: request.offset..<identityData.count)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        var result: CBATTError.Code = .success
        var marked = false
        for request in requests {
            if request.characteristic.uuid == markCharacteristicId,
               let value = request.value,
               let command = String(data: value, encoding: .utf8),
               command == "MARK" {
                marked = true
            } else {
                result = .attributeNotFound
            }
        }
        for request in requests { peripheral.respond(to: request, withResult: result) }
        if marked { DispatchQueue.main.async { self.onMark?() } }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        if let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService], !services.isEmpty {
            didAddService = true
        }
    }

    private func installService() {
        // Do not publish an empty or stale student identity if CoreBluetooth
        // reports .poweredOn before the QR profile has been installed.
        guard manager.state == .poweredOn, !didAddService, !identityData.isEmpty else { return }
        // A nil value makes this a dynamic characteristic. Reads are answered
        // by peripheralManager(_:didReceiveRead:) using the latest identityData.
        let identity = CBMutableCharacteristic(type: identityCharacteristicId, properties: [.read], value: nil, permissions: [.readable])
        let mark = CBMutableCharacteristic(type: markCharacteristicId, properties: [.write], value: nil, permissions: [.writeable])
        let service = CBMutableService(type: tapServiceId, primary: true)
        service.characteristics = [identity, mark]
        identityCharacteristic = identity
        markCharacteristic = mark
        manager.add(service)
    }
}

final class AttendanceModel: ObservableObject {
    @Published var profile: StudentQrPayload?
    @Published var status = ""
    @Published var scanning = false
    @Published var pendingCount = 0
    @Published var bluetoothStatus = "Запуск Bluetooth"
    private var peripheral: StudentPeripheral?
    private var peripheralCancellable: AnyCancellable?
    private let defaults = UserDefaults.standard
    private let profileKey = "visits11.student.profile.v1"
    private let queueKey = "visits11.student.pending.v1"
    private let deviceKey = "visits11.student.device-id.v1"
    private var uploadTask: Task<Void, Never>?
    private var retryTimer: Timer?

    init() {
        if let data = defaults.data(forKey: profileKey),
           let saved = try? JSONDecoder().decode(StudentQrPayload.self, from: data) {
            profile = saved
            status = "Поднесите телефон к телефону преподавателя"
            configurePeripheral()
        }
        updatePendingCount()
    }

    func acceptQr(_ raw: String) -> Bool {
        guard let data = raw.data(using: .utf8),
              let payload = try? JSONDecoder().decode(StudentQrPayload.self, from: data),
              payload.version == 1,
              payload.studentKey.range(of: "^[0-9a-fA-F]{32}$", options: .regularExpression) != nil,
              !payload.fullName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let serverUrl = URL(string: payload.serverUrl),
              serverUrl.scheme == "http" || serverUrl.scheme == "https",
              serverUrl.host != nil else {
            return false
        }
        profile = payload
        status = "Поднесите телефон к телефону преподавателя"
        if defaults.string(forKey: deviceKey) == nil {
            defaults.set(UUID().uuidString, forKey: deviceKey)
        }
        if let encoded = try? JSONEncoder().encode(payload) { defaults.set(encoded, forKey: profileKey) }
        configurePeripheral()
        scanning = false
        uploadPending()
        return true
    }

    func showScanner() {
        scanning = true
        status = ""
    }

    func finishScanning() {
        scanning = false
        if profile != nil { status = "Поднесите телефон к телефону преподавателя" }
    }

    func activate() {
        UIApplication.shared.isIdleTimerDisabled = profile != nil
        configurePeripheral()
        if retryTimer == nil {
            retryTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
                self?.uploadPending()
            }
        }
        uploadPending()
    }

    func recordMark() {
        guard let profile else { return }
        let deviceId = defaults.string(forKey: deviceKey) ?? UUID().uuidString
        defaults.set(deviceId, forKey: deviceKey)
        let record = MarkRecord(
            id: UUID().uuidString,
            studentKey: profile.studentKey,
            deviceId: deviceId,
            occurredAt: ISO8601DateFormatter().string(from: Date())
        )
        var queue = loadQueue()
        queue.append(record)
        saveQueue(queue)
        status = "\(profile.fullName)\nВы отмечены на паре"
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        uploadPending()
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self, self.profile != nil else { return }
            self.status = "Поднесите телефон к телефону преподавателя"
        }
    }

    func uploadPending() {
        guard uploadTask == nil, let profile, let base = URL(string: profile.serverUrl) else { return }
        let queue = loadQueue()
        guard !queue.isEmpty else { updatePendingCount(); return }
        uploadTask = Task { [weak self] in
            guard let self else { return }
            for item in queue {
                guard !Task.isCancelled,
                      let endpoint = URL(string: "/api/marks", relativeTo: base)?.absoluteURL else { break }
                var request = URLRequest(url: endpoint)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.timeoutInterval = 8
                request.httpBody = try? JSONEncoder().encode(item)
                do {
                    let (_, response) = try await URLSession.shared.data(for: request)
                    guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { break }
                    await MainActor.run { self.removeFromQueue(item.id) }
                } catch {
                    break
                }
            }
            await MainActor.run {
                self.uploadTask = nil
                self.updatePendingCount()
            }
        }
    }

    private func configurePeripheral() {
        guard let profile else { return }
        let deviceId = defaults.string(forKey: deviceKey) ?? UUID().uuidString
        defaults.set(deviceId, forKey: deviceKey)
        if peripheral == nil {
            let manager = StudentPeripheral()
            manager.onMark = { [weak self] in self?.recordMark() }
            peripheral = manager
            peripheralCancellable = manager.$bluetoothStatus.receive(on: DispatchQueue.main).sink { [weak self] value in
                self?.bluetoothStatus = value
            }
        }
        peripheral?.setIdentity(studentKey: profile.studentKey, fullName: profile.fullName, deviceId: deviceId)
    }

    private func loadQueue() -> [MarkRecord] {
        guard let data = defaults.data(forKey: queueKey),
              let records = try? JSONDecoder().decode([MarkRecord].self, from: data) else { return [] }
        return records
    }

    private func saveQueue(_ records: [MarkRecord]) {
        if let data = try? JSONEncoder().encode(records) { defaults.set(data, forKey: queueKey) }
        updatePendingCount()
    }

    private func removeFromQueue(_ id: String) {
        saveQueue(loadQueue().filter { $0.id != id })
    }

    private func updatePendingCount() {
        pendingCount = loadQueue().count
    }
}

struct ContentView: View {
    @StateObject private var model = AttendanceModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if model.profile == nil || model.scanning {
                ZStack(alignment: .top) {
                    QRScannerView { code in
                        if !model.acceptQr(code) { model.status = "QR-код не распознан" }
                    }
                    VStack(spacing: 10) {
                        Image("KubGAU").resizable().scaledToFit().frame(width: 54, height: 54)
                        Text("Отсканируйте QR-код студента")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(.white)
                        Text("Сканирование работает без интернета")
                            .font(.system(size: 13)).foregroundColor(.white.opacity(0.8))
                    }
                    .padding(.top, 38)
                }
                .ignoresSafeArea()
                .overlay(alignment: .bottom) {
                    if !model.status.isEmpty {
                        Text(model.status).font(.system(size: 13, weight: .medium)).foregroundColor(.white)
                            .padding(.horizontal, 16).padding(.vertical, 10)
                            .background(Color.black.opacity(0.65), in: Capsule()).padding(.bottom, 34)
                    }
                }
            } else {
                VStack(spacing: 20) {
                    Image("KubGAU").resizable().scaledToFit().frame(width: 116, height: 116)
                    Text(model.status.isEmpty ? "Поднесите телефон к телефону преподавателя" : model.status)
                        .font(.system(size: 16, weight: .medium))
                        .multilineTextAlignment(.center)
                        .foregroundColor(Color(red: 0.18, green: 0.28, blue: 0.23))
                    Text(model.bluetoothStatus)
                        .font(.system(size: 12)).foregroundColor(.secondary)
                    if model.pendingCount > 0 {
                        Text("Ожидают отправки: \(model.pendingCount)")
                            .font(.system(size: 12)).foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(red: 0.94, green: 0.97, blue: 0.95))
                .onLongPressGesture(minimumDuration: 1.2) { model.showScanner() }
            }
        }
        .onAppear { model.activate() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.activate() }
        }
    }
}

struct QRScannerView: UIViewControllerRepresentable {
    var onCode: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        let controller = QRScannerController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: QRScannerController, context: Context) {
        controller.onCode = onCode
    }
}

final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let captureSession = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didRead = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
            DispatchQueue.main.async {
                guard let self else { return }
                if allowed { self.configureCamera() }
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func configureCamera() {
        guard captureSession.inputs.isEmpty,
              let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera),
              captureSession.canAddInput(input) else { return }
        captureSession.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard captureSession.canAddOutput(output) else { return }
        captureSession.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: captureSession)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        previewLayer = layer
        DispatchQueue.global(qos: .userInitiated).async { self.captureSession.startRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !didRead,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        didRead = true
        captureSession.stopRunning()
        onCode?(value)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, self.view.window != nil else { return }
            self.didRead = false
            DispatchQueue.global(qos: .userInitiated).async { self.captureSession.startRunning() }
        }
    }
}
