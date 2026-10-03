import CoreData
import Foundation

/// Стек Core Data.
///
/// Модель описана прямо в коде (без .xcdatamodeld) — так проект собирается
/// из одного файла и не зависит от генераторов Xcode.
///
/// Сущности:
///   Mark    — отметки (очередь отправки на сервер, история);
///   Student — сам студент: логин, ФИО, device_id.
enum Persistence {

    static let shared: NSPersistentContainer = makeContainer()

    private static func makeContainer() -> NSPersistentContainer {
        let model = NSManagedObjectModel()

        // ---------------------------------------------------------------- Mark
        let mark = NSEntityDescription()
        mark.name = "Mark"
        mark.managedObjectClassName = NSStringFromClass(Mark.self)
        mark.properties = [
            attribute("id", .UUIDAttributeType),
            attribute("sessionId", .stringAttributeType),
            attribute("subject", .stringAttributeType),
            attribute("teacherId", .stringAttributeType),
            attribute("timestamp", .dateAttributeType),
            attribute("deviceId", .stringAttributeType),
            attribute("studentId", .stringAttributeType),
            attribute("studentName", .stringAttributeType),
            attribute("createdAt", .dateAttributeType),
            attribute("syncStateRaw", .integer16AttributeType, default: 0),
            attribute("serverMessage", .stringAttributeType, default: ""),
            attribute("verified", .booleanAttributeType, default: false),
        ]

        // ------------------------------------------------------------- Student
        let student = NSEntityDescription()
        student.name = "Student"
        student.managedObjectClassName = NSStringFromClass(Student.self)
        student.properties = [
            attribute("id", .stringAttributeType),
            attribute("name", .stringAttributeType, default: ""),
            attribute("deviceId", .stringAttributeType, default: ""),
            attribute("registeredAt", .dateAttributeType),
        ]

        model.entities = [mark, student]

        let container = NSPersistentContainer(name: "Visits11Student", managedObjectModel: model)
        if let description = container.persistentStoreDescriptions.first {
            description.shouldMigrateStoreAutomatically = true
            description.shouldInferMappingModelAutomatically = true
        }
        container.loadPersistentStores { _, error in
            if let error {
                // Локальная база — единственное место, где отметки ждут отправки,
                // поэтому о проблеме лучше узнать сразу в консоли.
                NSLog("Core Data: не удалось открыть хранилище — \(error)")
            }
        }
        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
        return container
    }

    private static func attribute(
        _ name: String,
        _ type: NSAttributeType,
        default defaultValue: Any? = nil
    ) -> NSAttributeDescription {
        let attribute = NSAttributeDescription()
        attribute.name = name
        attribute.attributeType = type
        attribute.isOptional = false
        attribute.defaultValue = defaultValue
        return attribute
    }
}
