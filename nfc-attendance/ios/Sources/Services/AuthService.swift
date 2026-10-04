import CryptoKit
import Foundation

/// Вход студента: проверка логина и пароля.
///
/// Как это работает:
///   1. первый вход — приложение спрашивает сервер (`POST /api/students/login`);
///      сервер хранит только хэш пароля (PBKDF2) и отвечает: верный / неверный /
///      такого логина нет / у логина ещё нет пароля;
///   2. после удачного входа приложение запоминает логин, ФИО и **хэш пароля
///      со своей солью** (в Keychain) — по нему можно проверить пароль
///      офлайн, если сервер недоступен (например, ещё не включили ноутбук);
///   3. пароль никуда не сохраняется в открытом виде.
@MainActor
final class AuthService: ObservableObject {

    static let shared = AuthService()

    struct Student: Equatable {
        let id: String
        let name: String
    }

    /// Кто вошёл. nil — показываем экран входа.
    @Published private(set) var student: Student?

    /// Вход проверен без сервера (по сохранённому хэшу) — показываем пометку.
    @Published private(set) var offlineLogin = false

    @Published private(set) var serverURL: String = ""

    enum Outcome: Equatable {
        case ok(offline: Bool)
        case wrongPassword(String)
        /// Логина нет или у него ещё нет пароля — нужно ФИО, чтобы создать аккаунт.
        case needsAccount(String)
        case failed(String)
    }

    private let settings = Settings.shared

    private enum Key {
        static let studentId = "auth.student_id"
        static let studentName = "auth.student_name"
        static func salt(_ id: String) -> String { "auth.salt.\(id)" }
        static func hash(_ id: String) -> String { "auth.hash.\(id)" }
    }

    private init() {
        restore()
    }

    // ---------------------------------------------------------------- локально

    /// Поднять сохранённого студента (запуск приложения).
    func restore() {
        serverURL = settings.serverURL
        guard let id = KeychainHelper.read(Key.studentId), !id.isEmpty else { return }
        let name = KeychainHelper.read(Key.studentName) ?? ""
        student = Student(id: id, name: name.isEmpty ? id : name)
        settings.studentId = id
        if !name.isEmpty { settings.studentName = name }
    }

    func logout() {
        KeychainHelper.delete(Key.studentId)
        KeychainHelper.delete(Key.studentName)
        student = nil
        offlineLogin = false
        settings.studentId = ""
        settings.studentName = ""
    }

    /// Хэш пароля для офлайн-проверки: SHA-256(соль + пароль).
    private func passwordHash(_ password: String, salt: String) -> String {
        let digest = SHA256.hash(data: Data((salt + password).utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func remember(studentId: String, name: String, password: String) {
        let existingSalt = KeychainHelper.read(Key.salt(studentId)) ?? ""
        let salt = existingSalt.isEmpty
            ? (0..<16).map { _ in String(format: "%02x", Int.random(in: 0...255)) }.joined()
            : existingSalt
        KeychainHelper.save(salt, for: Key.salt(studentId))
        KeychainHelper.save(passwordHash(password, salt: salt), for: Key.hash(studentId))
        KeychainHelper.save(studentId, for: Key.studentId)
        KeychainHelper.save(name, for: Key.studentName)
        student = Student(id: studentId, name: name.isEmpty ? studentId : name)
        settings.studentId = studentId
        settings.studentName = name
        serverURL = settings.serverURL
    }

    /// Совпадает ли введённый пароль с сохранённым (без сервера).
    private func matchesSavedPassword(studentId: String, password: String) -> Bool {
        guard KeychainHelper.read(Key.studentId) == studentId,
              let salt = KeychainHelper.read(Key.salt(studentId)),
              let saved = KeychainHelper.read(Key.hash(studentId)) else {
            return false
        }
        return saved == passwordHash(password, salt: salt)
    }

    // ------------------------------------------------------------------ сервер

    /// Вход: логин + пароль. Разбираем ответ сервера и объясняем студенту, что не так.
    func login(studentId rawId: String, password: String) async -> Outcome {
        let studentId = rawId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !studentId.isEmpty, !password.isEmpty else {
            return .failed("Введите логин и пароль")
        }
        guard let client = ApiClient(settings: settings) else {
            offlineLogin = false
            return .failed("Сначала укажите адрес сервера преподавателя")
        }

        switch await client.login(studentId: studentId, password: password) {
        case .ok(let info):
            offlineLogin = false
            remember(studentId: studentId, name: info.name ?? "", password: password)
            return .ok(offline: false)

        case .wrongPassword(let text):
            offlineLogin = false
            return .wrongPassword(text)

        case .unknownStudent(let text), .noPassword(let text):
            offlineLogin = false
            return .needsAccount(text)

        case .unavailable:
            // Сервера нет под рукой — пускаем по сохранённому хэшу пароля.
            if matchesSavedPassword(studentId: studentId, password: password) {
                let name = KeychainHelper.read(Key.studentName) ?? studentId
                student = Student(id: studentId, name: name)
                settings.studentId = studentId
                settings.studentName = name
                offlineLogin = true
                return .ok(offline: true)
            }
            offlineLogin = false
            return .failed("Сервер преподавателя недоступен. Первый вход на этот логин "
                           + "требует связи: проверьте адрес и Wi-Fi.")
        }
    }

    /// Создать аккаунт (первый вход): логин, ФИО, пароль.
    func register(studentId rawId: String, name: String, password: String) async -> Outcome {
        let studentId = rawId.trimmingCharacters(in: .whitespacesAndNewlines)
        let fullName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !fullName.isEmpty else { return .failed("Введите ФИО, как в журнале") }
        guard let client = ApiClient(settings: settings) else {
            return .failed("Сначала укажите адрес сервера преподавателя")
        }

        switch await client.register(studentId: studentId, name: fullName, password: password) {
        case .ok(let info):
            offlineLogin = false
            remember(studentId: studentId, name: info.name ?? fullName, password: password)
            return .ok(offline: false)

        case .unavailable:
            return .failed("Сервер преподавателя недоступен — создать аккаунт можно только онлайн.")

        case .wrongPassword(let text), .unknownStudent(let text), .noPassword(let text):
            return .failed(text)
        }
    }
}
