import SwiftUI

/// Настройки: адрес ноутбука преподавателя, логин и ФИО студента.
/// Всё сохраняется автоматически при вводе.
///
/// Тело экрана разбито на отдельные секции: длинные выражения SwiftUI
/// компилятор разбирает очень долго (или не разбирает вовсе).
struct SettingsView: View {

    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var store: AttendanceStore

    @State private var connectionState: String?
    @State private var isChecking = false

    var body: some View {
        NavigationStack {
            Form {
                serverSection
                studentSection
                deviceSection
                dataSection
                helpSection
            }
            .navigationTitle("Настройки")
        }
    }

    // ------------------------------------------------------------------ секции

    private var serverSection: some View {
        Section("Сервер преподавателя") {
            TextField("192.168.1.10", text: $settings.serverURL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button(action: checkConnection) {
                HStack {
                    Label(isChecking ? "Проверяем…" : "Проверить связь",
                          systemImage: "antenna.radiowaves.left.and.right")
                    Spacer()
                    if let connectionState {
                        Text(connectionState)
                            .foregroundStyle(connectionState == "есть" ? Color.green : Color.red)
                    }
                }
            }
            .disabled(isChecking || settings.baseURL == nil)
        }
    }

    private var studentSection: some View {
        Section("Студент") {
            TextField("Логин (например, ARHIPOV_II)", text: $settings.studentId)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            TextField("ФИО (для журнала)", text: $settings.studentName)
        }
    }

    private var deviceSection: some View {
        Section("Этот телефон") {
            labeled("device_id", DeviceIdentity.deviceId)
            labeled("Устройство", DeviceIdentity.deviceName)
            Text("device_id создаётся один раз и не меняется: сервер по нему "
                 + "не даёт отметиться за другого студента с этого телефона.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var dataSection: some View {
        Section("Данные на телефоне") {
            labeled("Всего отметок", String(store.marks.count))
            labeled("В очереди", String(store.pendingCount))

            Button(role: .destructive, action: { store.deleteAll() }) {
                Label("Удалить все отметки", systemImage: "trash")
            }
            .disabled(store.marks.isEmpty)
        }
    }

    private var helpSection: some View {
        Section("Как это работает") {
            Text("1. Преподаватель запускает сервер на ноутбуке и создаёт пару в приложении на Android.")
            Text("2. Телефон преподавателя превращается в NFC-метку (или показывает QR-код).")
            Text("3. Вы прикладываете iPhone (или сканируете QR) — отметка уходит на сервер по Wi-Fi.")
            Text("4. Без сети отметка остаётся в очереди и отправляется позже автоматически.")
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    // ---------------------------------------------------------------- элементы

    private func labeled(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.footnote, design: .monospaced))
                .textSelection(.enabled)
        }
    }

    private func checkConnection() {
        isChecking = true
        connectionState = nil
        Task {
            let ok = await SyncService.shared.checkConnection(settings: settings)
            connectionState = ok ? "есть" : "нет"
            isChecking = false
        }
    }
}

#Preview {
    SettingsView()
        .environmentObject(Settings.shared)
        .environmentObject(AttendanceStore.shared)
}
