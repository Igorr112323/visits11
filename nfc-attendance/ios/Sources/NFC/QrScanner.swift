import AVFoundation
import Foundation

/// Запасной способ отметки: чтение QR-кода камерой.
///
/// Зачем нужен: CoreNFC на iOS требует платного аккаунта разработчика Apple
/// (capability «NFC Tag Reading»). Камера же доступна всем — с бесплатной
/// подписью приложение работает по этому пути.
///
/// В QR лежит тот же JSON, что и в NFC-метке, поэтому логика отметки общая.
final class QrScanner: NSObject, ObservableObject {

    /// Сессия камеры — её показывает SwiftUI через CameraPreview.
    let session = AVCaptureSession()

    @Published private(set) var message: String = "Наведите камеру на QR-код на телефоне преподавателя"

    var onPayload: ((SessionPayload) -> Void)?

    private let queue = DispatchQueue(label: "ru.kubgau.qr.scanner")
    private var isConfigured = false
    private var hasDelivered = false

    // ------------------------------------------------------------------ запуск

    func start() {
        hasDelivered = false

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndRun()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    self?.configureAndRun()
                } else {
                    self?.report("Нет доступа к камере. Разрешите его в Настройках iPhone.")
                }
            }
        default:
            report("Нет доступа к камере. Настройки iPhone → КубГАУ Студент → Камера.")
        }
    }

    func stop() {
        queue.async {
            if self.session.isRunning {
                self.session.stopRunning()
            }
        }
    }

    // ------------------------------------------------------------- настройка

    private func configureAndRun() {
        queue.async {
            if !self.isConfigured {
                self.configure()
                self.isConfigured = true
            }
            guard self.isConfigured, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            report("Камера недоступна — отметка по QR невозможна.")
            return
        }
        session.beginConfiguration()
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            report("Не удалось включить распознавание QR-кодов.")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]
        session.commitConfiguration()
    }

    private func report(_ text: String) {
        DispatchQueue.main.async { self.message = text }
    }
}

// --------------------------------------------------------- распознавание кодов

extension QrScanner: AVCaptureMetadataOutputObjectsDelegate {

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !hasDelivered else { return }
        guard let code = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let text = code.stringValue else { return }

        guard let payload = SessionPayload.parse(recordType: nil, payload: Data(text.utf8)) else {
            report("Это не код пары КубГАУ. Наведите камеру на QR на телефоне преподавателя.")
            return
        }

        hasDelivered = true
        stop()
        DispatchQueue.main.async {
            self.onPayload?(payload)
        }
    }
}
