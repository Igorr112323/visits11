import AudioToolbox
import AVFoundation
import SwiftUI
import UIKit

/// Сканер QR-кода камерой: экран во весь экран с подсказкой и кнопкой «Отмена».
/// Системные средства (AVFoundation), без сторонних библиотек и без интернета.
struct CameraScanner: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    var onCancel: () -> Void

    func makeUIViewController(context: Context) -> QrScannerViewController {
        let controller = QrScannerViewController()
        controller.onCode = onCode
        controller.onCancel = onCancel
        return controller
    }

    func updateUIViewController(_ controller: QrScannerViewController, context: Context) {
    }
}

final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onCode: ((String) -> Void)?
    var onCancel: (() -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "ru.visits11.student.camera")
    private var preview: AVCaptureVideoPreviewLayer?
    private var handled = false
    // Accessed only on sessionQueue.
    private var wantsCamera = true
    private var configured = false

    private let hintLabel = UILabel()
    private let cancelButton = UIButton(type: .system)

    // MARK: жизненный цикл

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        addOverlay()
        prepareCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.wantsCamera = false
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.wantsCamera = true
            if self.configured && !self.session.isRunning { self.session.startRunning() }
        }
    }

    // MARK: камера

    private func prepareCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureAndStart()
                    } else {
                        self?.showMessage("Нет доступа к камере — включите его в Настройках")
                    }
                }
            }
        default:
            showMessage("Нет доступа к камере — включите его в Настройках")
        }
    }

    private func configureAndStart() {
        // Configuration as well as start/stop must not block SwiftUI sheet dismissal.
        sessionQueue.async { [weak self] in
            guard let self = self, self.wantsCamera, !self.configured else { return }
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  self.session.canAddInput(input) else {
                DispatchQueue.main.async { self.showMessage("Камера недоступна — выберите картинку из галереи") }
                return
            }
            self.session.beginConfiguration()
            self.session.addInput(input)
            let output = AVCaptureMetadataOutput()
            if self.session.canAddOutput(output) {
                self.session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
            self.session.commitConfiguration()
            self.configured = true
            DispatchQueue.main.async {
                let preview = AVCaptureVideoPreviewLayer(session: self.session)
                preview.videoGravity = .resizeAspectFill
                preview.frame = self.view.bounds
                self.view.layer.insertSublayer(preview, at: 0)
                self.preview = preview
            }
            if self.wantsCamera && !self.session.isRunning { self.session.startRunning() }
        }
    }

    private func showMessage(_ text: String) {
        hintLabel.text = text
        hintLabel.textColor = .white
    }

    // MARK: распознавание

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !handled,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else {
            return
        }
        handled = true

        let session = self.session
        sessionQueue.async {
            if session.isRunning {
                session.stopRunning()
            }
        }
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        onCode?(value)
    }

    // MARK: оформление

    private func addOverlay() {
        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        hintLabel.text = "Наведите камеру на QR-код студента"
        hintLabel.textColor = .white
        hintLabel.numberOfLines = 0
        hintLabel.textAlignment = .center
        hintLabel.font = .preferredFont(forTextStyle: .headline)

        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.setTitle("Отмена", for: .normal)
        cancelButton.setTitleColor(.white, for: .normal)
        cancelButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

        view.addSubview(hintLabel)
        view.addSubview(cancelButton)

        NSLayoutConstraint.activate([
            hintLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            hintLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            hintLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 32),

            cancelButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancelButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
        ])
    }

    @objc private func cancelTapped() {
        onCancel?()
    }
}
