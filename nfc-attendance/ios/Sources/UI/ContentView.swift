import SwiftUI

/// Корневой экран приложения студента.
///
/// Всего два состояния — никаких вкладок:
///   • студент ещё не вошёл → экран входа (логин + пароль + одна кнопка);
///   • вошёл → экран отметки с одной большой кнопкой «Приложить телефон».
struct ContentView: View {

    @EnvironmentObject private var store: AttendanceStore
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var auth: AuthService

    var body: some View {
        Group {
            if auth.student != nil {
                MarkView()
            } else {
                LoginView()
            }
        }
        .animation(.easeInOut(duration: 0.25), value: auth.student)
        .task {
            auth.restore()
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(AttendanceStore.shared)
        .environmentObject(Settings.shared)
        .environmentObject(AuthService.shared)
}
