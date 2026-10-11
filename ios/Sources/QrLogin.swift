import Foundation
import PhotosUI
import SwiftUI
import UIKit
import Vision

// MARK: - формат QR-кода студента

/// QR-код, который экспортирует ПК (см. Services/StudentQr.cs):
///
///     V11S1.<логин>.<пароль>.<ФИО>.<CRC32>   — все поля в base64url
///     Login: ARHIPOV_II
///     Password: K7M2QX9P
///
/// Приложение читает первую строку; две нижние — для человека: можно
/// отсканировать код любым QR-сканером и ввести логин с паролем руками.
struct StudentQrCredentials {
    let login: String
    let password: String
    let name: String

    /// Версия формата — та же строка, что на ПК и в Android-приложении.
    static let prefix = "V11S1"

    /// Разбирает содержимое QR. Возвращает nil, если это не наш код
    /// (чужая картинка, обрезанный снимок, QR переклички и т.п.).
    static func parse(_ text: String) -> StudentQrCredentials? {
        let firstLine = text
            .split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
            .first
            .map { String($0) } ?? ""
        let machine = firstLine.trimmingCharacters(in: .whitespacesAndNewlines)

        let parts = machine.components(separatedBy: ".")
        guard parts.count == 5, parts[0] == prefix else { return nil }

        // CRC считается от машинной строки без последнего поля — так отсеиваются
        // чужие картинки и кривые распознавания
        let body = parts[0...3].joined(separator: ".")
        guard crc32Hex(body) == parts[4].lowercased() else { return nil }

        guard let login = base64UrlDecode(parts[1]), !login.isEmpty,
              let password = base64UrlDecode(parts[2]), !password.isEmpty else { return nil }

        return StudentQrCredentials(login: login, password: password, name: base64UrlDecode(parts[3]) ?? "")
    }

    /// base64url без выравнивания.
    static func base64UrlDecode(_ value: String) -> String? {
        var normalized = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = (4 - normalized.count % 4) % 4
        normalized += String(repeating: "=", count: padding)
        guard let data = Data(base64Encoded: normalized) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// CRC32 (IEEE 802.3), 8 hex-символов — тот же алгоритм, что на ПК и на Android.
    static func crc32Hex(_ value: String) -> String {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in value.utf8 {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                crc = (crc & 1) == 1 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1
            }
        }
        return String(format: "%08x", crc ^ 0xFFFF_FFFF)
    }
}

// MARK: - распознавание QR на картинке

/// Чтение QR-кода с картинки средствами системы (Vision):
/// ни интернета, ни разрешений, ни сторонних библиотек.
enum QrImageDecoder {
    static func text(from image: UIImage) -> String? {
        guard let cgImage = image.cgImage else { return nil }

        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        return (request.results ?? []).compactMap { $0.payloadStringValue }.first
    }
}

// MARK: - системный выбор картинки

/// Системный выбор фото (PHPicker): работает вне нашего процесса, поэтому
/// ни разрешения на галерею, ни ключей в Info.plist не требуется —
/// iOS сама отдаёт выбранную картинку.
struct PhotoPicker: UIViewControllerRepresentable {
    var onPick: (UIImage) -> Void
    var onFinish: () -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onFinish: onFinish)
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPick: (UIImage) -> Void
        private let onFinish: () -> Void

        init(onPick: @escaping (UIImage) -> Void, onFinish: @escaping () -> Void) {
            self.onPick = onPick
            self.onFinish = onFinish
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            onFinish()

            guard let provider = results.first?.itemProvider,
                  provider.canLoadObject(ofClass: UIImage.self) else { return }

            provider.loadObject(ofClass: UIImage.self) { [onPick] object, _ in
                guard let image = object as? UIImage else { return }
                DispatchQueue.main.async { onPick(image) }
            }
        }
    }
}
