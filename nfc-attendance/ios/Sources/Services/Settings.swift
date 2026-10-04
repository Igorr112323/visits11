import Foundation
import SwiftUI

/// Настройки студента: адрес сервера преподавателя, логин и ФИО.
///
/// Значения пишутся в UserDefaults при каждом изменении — экран «Настройки»
/// можно закрыть, ничего не «сохраняя».
@MainActor
final class Settings: ObservableObject {

    static let shared = Settings()

    private enum Key {
        static let serverURL = "server_url"
        static let studentId = "student_id"
        static let studentName = "student_name"
    }

    @Published var serverURL: String {
        didSet { UserDefaults.standard.set(serverURL, forKey: Key.serverURL) }
    }

    @Published var studentId: String {
        didSet { UserDefaults.standard.set(studentId, forKey: Key.studentId) }
    }

    @Published var studentName: String {
        didSet { UserDefaults.standard.set(studentName, forKey: Key.studentName) }
    }

    private init() {
        let defaults = UserDefaults.standard
        serverURL = defaults.string(forKey: Key.serverURL) ?? ""
        studentId = defaults.string(forKey: Key.studentId) ?? ""
        studentName = defaults.string(forKey: Key.studentName) ?? ""
    }

    /// Адрес сервера в виде URL: «192.168.1.10» → http://192.168.1.10:8000.
    var baseURL: URL? {
        var text = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.hasPrefix("http://") && !text.hasPrefix("https://") {
            text = "http://" + text
        }
        // если порт не указан явно — подставляем 8000 (uvicorn из инструкции)
        let afterScheme = text.components(separatedBy: "://").dropFirst().joined(separator: "://")
        let hostAndPath = afterScheme.split(separator: "/", maxSplits: 1).first.map(String.init) ?? ""
        if !hostAndPath.contains(":") {
            text = text.replacingOccurrences(of: hostAndPath, with: hostAndPath + ":8000")
        }
        return URL(string: text)
    }

    var isConfigured: Bool {
        baseURL != nil && !studentId.trimmingCharacters(in: .whitespaces).isEmpty
    }
}
