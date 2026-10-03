import Foundation

/// Отправка отметок на сервер преподавателя — с очередью и повторами.
///
/// Логика:
///   • каждая отметка сначала ложится в Core Data (syncState = .pending);
///   • здесь она уезжает на POST /api/attendance;
///   • 200 → .sent (или .duplicate), 404/409 → .rejected с текстом причины;
///   • ошибка сети → отметка остаётся .pending и уйдёт при следующей попытке;
///   • повторы: при запуске, после каждого касания, при возврате в приложение
///     и по кнопке «Отправить» (плюс автоматически — таймером ниже).
@MainActor
final class SyncService {

    static let shared = SyncService()

    private var isSyncing = false
    private var retryTask: Task<Void, Never>?

    private init() {}

    /// Отправить все неотправленные отметки.
    func syncPending(store: AttendanceStore, settings: Settings = .shared) async {
        guard !isSyncing else { return }
        guard let baseURL = settings.baseURL else { return }

        let pending = store.marks.filter { $0.syncState == .pending }
        guard !pending.isEmpty else { return }

        isSyncing = true
        defer { isSyncing = false }

        let client = ApiClient(baseURL: baseURL)
        var networkFailures = 0

        for mark in pending {
            let body = ApiClient.MarkBody(
                sessionId: mark.sessionId,
                studentId: mark.studentId,
                deviceId: mark.deviceId,
                timestamp: SessionPayload.isoString(mark.timestamp),
                subject: mark.subject,
                teacherId: mark.teacherId,
                studentName: mark.studentName,
                source: "ios",
            )

            do {
                let result = try await client.sendMark(body)
                switch result.result {
                case "duplicate":
                    store.update(mark, state: .duplicate, message: result.detail,
                                 verified: result.verified)
                case "rejected":
                    store.update(mark, state: .rejected, message: result.detail)
                default:
                    store.update(mark, state: .sent, message: result.detail,
                                 verified: result.verified)
                }
            } catch {
                // сервер недоступен: отметка остаётся в очереди
                networkFailures += 1
                store.update(mark, state: .pending,
                             message: "Нет связи с сервером — отправим позже")
            }
        }

        store.lastResult = networkFailures == 0
            ? "Все отметки отправлены"
            : "Сервер недоступен: \(networkFailures) отметк(и) в очереди"

        if networkFailures > 0 {
            scheduleRetry(store: store)
        }
    }

    /// Повторная попытка: 5 с, 15 с, 45 с, 2 мин … (пока не получится).
    func scheduleRetry(store: AttendanceStore, attempt: Int = 0) {
        retryTask?.cancel()
        let delay = min(5.0 * pow(3.0, Double(attempt)), 120.0)
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            await self.syncPending(store: store)
            let stillPending = store.marks.contains { $0.syncState == .pending }
            if stillPending {
                self.scheduleRetry(store: store, attempt: attempt + 1)
            }
        }
    }

    func cancelRetry() {
        retryTask?.cancel()
        retryTask = nil
    }

    /// Ручная проверка связи (экран «Настройки»).
    func checkConnection(settings: Settings) async -> Bool {
        guard let baseURL = settings.baseURL else { return false }
        return await ApiClient(baseURL: baseURL).health()
    }

    /// Список присутствующих на паре (экран «Мои отметки» → детали).
    func present(sessionId: String, settings: Settings) async -> [ApiClient.PresentRow] {
        guard let baseURL = settings.baseURL else { return [] }
        return await ApiClient(baseURL: baseURL).attendance(sessionId: sessionId)
    }

    /// Убедиться, что сервер знает о паре (если телефон преподавателя ещё не успел
    /// синхронизироваться — создаём пару сами по данным метки).
    func ensureSessionExists(payload: SessionPayload, settings: Settings) async {
        guard let baseURL = settings.baseURL else { return }
        let client = ApiClient(baseURL: baseURL)
        if await client.sessionInfo(sessionId: payload.sessionId) == nil {
            _ = await client.createSession(
                sessionId: payload.sessionId,
                subject: payload.subject,
                teacherId: payload.teacherId,
            )
        }
    }
}
