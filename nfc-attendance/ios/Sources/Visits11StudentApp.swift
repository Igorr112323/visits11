import SwiftUI

/// Точка входа приложения студента.
///
/// Что происходит при запуске:
///   1. поднимается Core Data (локальные отметки, очередь отправки);
///   2. приложение проверяет связь с сервером преподавателя и досылает
///      всё, что не уехало в прошлый раз;
///   3. открывается экран «Отметиться».
@main
struct Visits11StudentApp: App {

    @StateObject private var store = AttendanceStore.shared
    @StateObject private var settings = Settings.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(settings)
                .task {
                    // при старте — досылаем очередь и обновляем список
                    await SyncService.shared.syncPending(store: store)
                }
        }
        .onChange(of: scenePhase) { phase in
            // вернулись в приложение — пробуем отправить неотправленное
            if phase == .active {
                Task { await SyncService.shared.syncPending(store: store) }
            }
        }
    }
}
