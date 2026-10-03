import Foundation

/// Данные, записанные в NDEF-метке телефона преподавателя.
///
/// Ровно тот же JSON, что собирает Android-приложение:
///   {"session_id":"…UUID…","subject":"Информатика",
///    "teacher_id":"IVANOV_II","timestamp":"2026-10-04T10:15:00+03:00"}
struct SessionPayload: Codable, Equatable {

    let sessionId: String
    let subject: String
    let teacherId: String
    let timestamp: Date

    enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
        case subject
        case teacherId = "teacher_id"
        case timestamp
    }

    // -------------------------------------------------------------- разбор NDEF

    /// Разбирает полезную нагрузку NDEF-записи.
    /// Поддерживаются: наш MIME-тип (JSON), текстовые записи (T) и «сырой» JSON.
    static func parse(recordType: String?, payload: Data) -> SessionPayload? {
        var data = payload

        // Текстовая запись NDEF: первый байт — длина кода языка, затем сам текст
        if let type = recordType, type == "T", data.count > 1 {
            let languageLength = Int(data[data.startIndex])
            if data.count > languageLength {
                data = data.dropFirst(languageLength + 1)
            }
        }

        if let payload = try? decoder.decode(SessionPayload.self, from: data) {
            return payload
        }

        // Метаданные MIME-записи могут содержать параметры — пробуем найти JSON в тексте
        if let text = String(data: data, encoding: .utf8),
           let start = text.firstIndex(of: "{"),
           let end = text.lastIndex(of: "}"),
           start <= end,
           let json = text[start...end].data(using: .utf8),
           let payload = try? decoder.decode(SessionPayload.self, from: json) {
            return payload
        }

        return nil
    }

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let text = try container.decode(String.self)
            if let date = parseDate(text) { return date }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Не удалось разобрать дату: \(text)"
            )
        }
        return decoder
    }()

    /// Дата ISO-8601 с таймзоной — формат и Android, и сервера.
    static func parseDate(_ text: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFraction.date(from: text) { return date }

        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: text)
    }

    /// Дата в ISO-8601 с локальной таймзоной — то, что уходит на сервер.
    static func isoString(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone.current
        return formatter.string(from: date)
    }

    /// Изменилась ли метка за последние минуты (защита от «старых» меток).
    func isFresh(within seconds: TimeInterval = 24 * 3600) -> Bool {
        abs(timestamp.timeIntervalSinceNow) <= seconds
    }
}
