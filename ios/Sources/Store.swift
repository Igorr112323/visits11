import Foundation
import Security

enum Store {
    private static let defaults = UserDefaults.standard
    private static let service = "ru.visits11.student"

    static var login: String? {
        get { defaults.string(forKey: "login") }
        set { defaults.set(newValue, forKey: "login") }
    }

    static var password: String? {
        get { defaults.string(forKey: "password") }
        set { defaults.set(newValue, forKey: "password") }
    }

    static var host: String? {
        get { defaults.string(forKey: "host") }
        set { defaults.set(newValue, forKey: "host") }
    }

    static var token: String? {
        get { defaults.string(forKey: "token") }
        set { defaults.set(newValue, forKey: "token") }
    }

    static func deviceId() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "device"
        ]

        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        if SecItemCopyMatching(read as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let saved = String(data: data, encoding: .utf8),
           !saved.isEmpty {
            return saved
        }

        if let saved = defaults.string(forKey: "device"), !saved.isEmpty {
            return saved
        }

        let created = UUID().uuidString
        defaults.set(created, forKey: "device")
        var add = query
        add[kSecValueData as String] = Data(created.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
        return created
    }
}
