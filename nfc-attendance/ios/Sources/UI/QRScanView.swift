import AVFoundation
import SwiftUI

/// Экран сканирования QR-кода: показываем камеру и ждём код пары.
struct QRScanView: View {

    let onPayload: (SessionPayload) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var scanner = QrScanner()

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                CameraPreview(session: scanner.session)
                    .frame(maxWidth: .infinity)
                    .frame(height: 360)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .strokeBorder(Color.white.opacity(0.6), lineWidth: 2)
                    )

                Text(scanner.message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Button("Отмена") { dismiss() }
                    .buttonStyle(.bordered)
            }
            .padding(16)
            .navigationTitle("QR-код пары")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                scanner.onPayload = { payload in
                    onPayload(payload)
                    dismiss()
                }
                scanner.start()
            }
            .onDisappear {
                scanner.stop()
            }
        }
    }
}

/// Мост между UIKit-слоем камеры и SwiftUI.
struct CameraPreview: UIViewRepresentable {

    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.previewLayer.session = session
    }

    /// UIView, у которого слой — AVCaptureVideoPreviewLayer.
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}

#Preview {
    QRScanView { _ in }
}
