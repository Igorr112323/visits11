import SwiftUI

/// Корневой экран: три вкладки — отметка, история, настройки.
struct ContentView: View {

    @EnvironmentObject private var store: AttendanceStore
    @EnvironmentObject private var settings: Settings

    var body: some View {
        TabView {
            MarkView()
                .tabItem { Label("Отметиться", systemImage: "checkmark.seal") }

            HistoryView()
                .tabItem { Label("Мои отметки", systemImage: "list.bullet.rectangle") }

            SettingsView()
                .tabItem { Label("Настройки", systemImage: "gearshape") }
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(AttendanceStore.shared)
        .environmentObject(Settings.shared)
}
