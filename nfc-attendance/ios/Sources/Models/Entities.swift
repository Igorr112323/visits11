import CoreData
import Foundation

/// Состояние отправки отметки на сервер.
enum MarkSyncState: Int16 {
    case pending = 0      // ждёт отправки (нет сети / сервер выключен)
    case sent = 1         // сервер принял
    case rejected = 2     // сервер отказал (дубль, чужая пара, чужой телефон)
    case duplicate = 3    // уже отмечался в эту пару
}

/// Отметка студента (локально, Core Data).
@objc(Mark)
public final class Mark: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var sessionId: String
    @NSManaged public var subject: String
    @NSManaged public var teacherId: String
    @NSManaged public var timestamp: Date        // момент чтения NFC-метки
    @NSManaged public var deviceId: String
    @NSManaged public var studentId: String
    @NSManaged public var studentName: String
    @NSManaged public var createdAt: Date
    @NSManaged public var syncStateRaw: Int16
    @NSManaged public var serverMessage: String
    @NSManaged public var verified: Bool
    @NSManaged public var source: String       // ble (касание по Bluetooth) | nfc
    @NSManaged public var rssi: Int16          // уровень сигнала, дБм; 0 — неизвестно

    var syncState: MarkSyncState {
        get { MarkSyncState(rawValue: syncStateRaw) ?? .pending }
        set { syncStateRaw = newValue.rawValue }
    }

    @nonobjc public class func fetchRequest() -> NSFetchRequest<Mark> {
        NSFetchRequest<Mark>(entityName: "Mark")
    }
}

extension Mark: Identifiable {}

/// Студент (локально на телефоне): логин и привязанный device_id.
@objc(Student)
public final class Student: NSManagedObject {
    @NSManaged public var id: String
    @NSManaged public var name: String
    @NSManaged public var deviceId: String
    @NSManaged public var registeredAt: Date

    @nonobjc public class func fetchRequest() -> NSFetchRequest<Student> {
        NSFetchRequest<Student>(entityName: "Student")
    }
}

extension Student: Identifiable {}
