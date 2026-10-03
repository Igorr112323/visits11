import Foundation
import Security
import UIKit

/// Идентификатор устройства (device_id).
///
/// Генерируется один раз и хранится в Keychain: переживает перезапуск,
/// а также переустановку приложения (в отличие от UserDefaults).
/// Именно по нему сервер блокирует отметки «за соседа» с чужого телефона.
enum DeviceIdentity {

    private static let service = "ru.kubgau.attendance.student"
    private static let account = "device_id"

    /// Стабильный ID: KEYCHAIN → (миграция) UserDefaults → новый UUID.
    static var deviceId: String {
        if let existing = readFromKeychain() {
            return existing
        }
        if let legacy = UserDefaults.standard.string(forKey: "device_id"), !legacy.isEmpty {
            saveToKeychain(legacy)
            return legacy
        }
        let generated = "IOS-" + UUID().uuidString
        saveToKeychain(generated)
        return generated
    }

    /// Человекочитаемое описание устройства — уходит на сервер вместе с отметкой.
    static var deviceName: String {
        "\(UIDevice.current.model) · iOS \(UIDevice.current.systemVersion)"
    }

    // ----------------------------------------------------------------- Keychain

    private static func readFromKeychain() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func saveToKeychain(_ value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }
}
