import CoreData
import Foundation

/// Хранилище отметок: сохраняет, читает и держит очередь на отправку.
///
/// Схема работы студента:
///   1. приложение прочитало NFC-метку и создало Mark (syncState = .pending);
///   2. SyncService пытается отправить его на сервер;
///   3. не получилось — Mark остаётся в очереди и уходит при следующей попытке
///      (запуск приложения, новое касание, кнопка «Отправить»).
@MainActor
final class AttendanceStore: ObservableObject {

    static let shared = AttendanceStore()

    @Published private(set) var marks: [Mark] = []
    @Published private(set) var pendingCount: Int = 0
    @Published private(set) var lastResult: String = ""

    private let container = Persistence.shared
    private var context: NSManagedObjectContext { container.viewContext }

    private init() {
        reload()
    }

    // ------------------------------------------------------------------ чтение

    func reload() {
        let request = Mark.fetchRequest()
        request.sortDescriptors = [NSSortDescriptor(keyPath: \Mark.createdAt, ascending: false)]
        marks = (try? context.fetch(request)) ?? []
        pendingCount = marks.filter { $0.syncState == .pending }.count
    }

    /// Все отметки конкретной пары (для повторной отправки).
    func marks(sessionId: String) -> [Mark] {
        marks.filter { $0.sessionId == sessionId }
    }

    // ------------------------------------------------------------------ запись

    /// Сохранить отметку, прочитанную с NFC-метки.
    @discardableResult
    func addMark(payload: SessionPayload, settings: Settings) -> Mark {
        let mark = Mark(context: context)
        mark.id = UUID()
        mark.sessionId = payload.sessionId
        mark.subject = payload.subject
        mark.teacherId = payload.teacherId
        mark.timestamp = payload.timestamp
        mark.deviceId = DeviceIdentity.deviceId
        mark.studentId = settings.studentId.trimmingCharacters(in: .whitespaces)
        mark.studentName = settings.studentName
        mark.createdAt = Date()
        mark.syncState = .pending
        mark.serverMessage = ""
        mark.verified = false

        save()
        reload()
        return mark
    }

    /// Сохранить итог последней отправки (для интерфейса).
    func setLastResult(_ text: String) {
        lastResult = text
    }

    /// Отметить результат отправки.
    func update(_ mark: Mark, state: MarkSyncState, message: String, verified: Bool = false) {
        mark.syncState = state
        mark.serverMessage = message
        mark.verified = verified
        save()
        reload()
    }

    /// Удалить отметку (например, ошибочную с чужой пары).
    func delete(_ mark: Mark) {
        context.delete(mark)
        save()
        reload()
    }

    func deleteAll() {
        marks.forEach(context.delete)
        save()
        reload()
    }

    // ------------------------------------------------------------------ личность

    /// Студент, привязанный к этому телефону (для проверки «тот ли телефон»).
    func student() -> Student? {
        let request = Student.fetchRequest()
        request.fetchLimit = 1
        return try? context.fetch(request).first
    }

    /// Сохранить/обновить карточку студента на телефоне.
    func saveStudent(id: String, name: String) {
        let request = Student.fetchRequest()
        request.predicate = NSPredicate(format: "id == %@", id)
        let student = (try? context.fetch(request).first) ?? Student(context: context)
        student.id = id
        student.name = name
        student.deviceId = DeviceIdentity.deviceId
        student.registeredAt = Date()
        save()
    }

    // ------------------------------------------------------------------ служебное

    private func save() {
        guard context.hasChanges else { return }
        do {
            try context.save()
        } catch {
            NSLog("Core Data: не удалось сохранить — \(error)")
            context.rollback()
        }
    }
}
