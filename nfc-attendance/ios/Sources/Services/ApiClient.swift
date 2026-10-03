import Foundation

/// Клиент локального сервера преподавателя (FastAPI, порт 8000).
///
/// Все запросы короткие: сервер рядом, в той же Wi-Fi-сети, ждать нечего.
struct ApiClient {

    let baseURL: URL

    private var session: URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 6
        configuration.timeoutIntervalForResource = 12
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }

    // ------------------------------------------------------------------ модели

    struct SendResult {
        let ok: Bool
        let result: String        // accepted | accepted_unverified | duplicate
        let detail: String
        let verified: Bool
        let rejected: Bool        // сервер отказал — повторять бессмысленно
        let reason: String?
    }

    struct SessionInfo: Codable {
        let id: String
        let subject: String
        let teacherId: String?
        let groupName: String?
        let startTime: String?
        let endTime: String?
        let status: String?
        let isOpen: Bool?

        enum CodingKeys: String, CodingKey {
            case id, subject, status
            case teacherId = "teacher_id"
            case groupName = "group_name"
            case startTime = "start_time"
            case endTime = "end_time"
            case isOpen = "is_open"
        }
    }

    private struct SessionResponse: Codable {
        let ok: Bool
        let session: SessionInfo?
    }

    struct PresentRow: Codable, Identifiable {
        let studentId: String
        let studentName: String?
        let timestamp: String?
        let verified: Int?

        var id: String { studentId }

        enum CodingKeys: String, CodingKey {
            case timestamp, verified
            case studentId = "student_id"
            case studentName = "student_name"
        }
    }

    private struct AttendanceResponse: Codable {
        let ok: Bool
        let presentCount: Int?
        let present: [PresentRow]?

        enum CodingKeys: String, CodingKey {
            case ok, present
            case presentCount = "present_count"
        }
    }

    private struct ErrorResponse: Codable {
        let reason: String?
        let detail: String?
    }

    /// Тело отметки — ровно то, что ждёт POST /api/attendance.
    struct MarkBody: Codable {
        let sessionId: String
        let studentId: String
        let deviceId: String
        let timestamp: String
        let subject: String
        let teacherId: String
        let studentName: String
        let source: String

        enum CodingKeys: String, CodingKey {
            case subject, timestamp, source
            case sessionId = "session_id"
            case studentId = "student_id"
            case deviceId = "device_id"
            case teacherId = "teacher_id"
            case studentName = "student_name"
        }
    }

    // ------------------------------------------------------------------ запросы

    /// Проверка связи: GET /api/health.
    func health() async -> Bool {
        guard let url = URL(string: "/api/health", relativeTo: baseURL) else { return false }
        do {
            let (data, response) = try await session.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return false }
            return String(data: data, encoding: .utf8)?.contains("visits11-local-server") ?? true
        } catch {
            return false
        }
    }

    /// Создать пару (нужно, если сервер ещё не знает о ней).
    func createSession(
        sessionId: String,
        subject: String,
        teacherId: String,
        minutes: Int = 120,
    ) async -> Bool {
        guard let url = URL(string: "/api/session", relativeTo: baseURL) else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "session_id": sessionId,
            "subject": subject,
            "teacher_id": teacherId,
            "minutes": minutes,
        ])
        do {
            let (_, response) = try await session.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }

    /// Информация о паре: GET /api/session/{id}.
    func sessionInfo(sessionId: String) async -> SessionInfo? {
        guard let url = URL(string: "/api/session/\(sessionId)", relativeTo: baseURL) else { return nil }
        do {
            let (data, response) = try await session.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
            return try JSONDecoder().decode(SessionResponse.self, from: data).session
        } catch {
            return nil
        }
    }

    /// Отправить отметку: POST /api/attendance.
    func sendMark(_ body: MarkBody) async throws -> SendResult {
        guard let url = URL(string: "/api/attendance", relativeTo: baseURL) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0

        switch status {
        case 200:
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            let result = json["result"] as? String ?? "accepted"
            let detail = json["detail"] as? String ?? "Отметка принята"
            let verified = (json["verified"] as? Bool) ?? (result == "accepted")
            return SendResult(
                ok: true,
                result: result,
                detail: detail,
                verified: verified,
                rejected: false,
                reason: nil,
            )

        case 404, 409:
            let error = try? JSONDecoder().decode(ErrorResponse.self, from: data)
            return SendResult(
                ok: false,
                result: "rejected",
                detail: error?.detail ?? "Сервер отклонил отметку",
                verified: false,
                rejected: true,
                reason: error?.reason,
            )

        default:
            throw URLError(.badServerResponse)
        }
    }

    /// Список присутствующих: GET /api/attendance/{session_id}.
    func attendance(sessionId: String) async -> [PresentRow] {
        guard let url = URL(string: "/api/attendance/\(sessionId)", relativeTo: baseURL) else { return [] }
        do {
            let (data, _) = try await session.data(from: url)
            return try JSONDecoder().decode(AttendanceResponse.self, from: data).present ?? []
        } catch {
            return []
        }
    }
}
