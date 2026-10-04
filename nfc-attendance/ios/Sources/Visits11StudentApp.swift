import SwiftUI

/// Точка входа приложения студента.
///
/// Что происходит при запуске:
///   1. поднимается Core Data (локальные отметки, очередь отправки);
///   2. восстанавливается вход студента (логин и хэш пароля лежат в Keychain);
///   3. приложение проверяет связь с сервером и досылает всё, что не уехало;
///   4. открывается один из двух экранов: вход (если не вошёл) или отметка.
@main
struct Visits11StudentApp: App {

    @StateObject private var store = AttendanceStore.shared
    @StateObject private var settings = Settings.shared
    @StateObject private var auth = AuthService.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(settings)
                .environmentObject(auth)
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
