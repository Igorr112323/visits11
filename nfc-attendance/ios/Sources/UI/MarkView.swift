import SwiftUI

/// Экран отметки — как в Android: одна большая кнопка и статус.
///
/// Студент вошёл под логином и паролем (проверил сервер), дальше ему нужно
/// только приложить телефон. Кнопка — это единственное действие на экране:
/// нажатие запускает поиск метки терминала (и перезапускает его, если что-то
/// не сработало). Работает Bluetooth-касание; NFC доступен отдельной строкой внизу.
struct MarkView: View {

    @EnvironmentObject private var store: AttendanceStore
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var auth: AuthService

    @StateObject private var ble = BleMarkClient()
    @StateObject private var nfc = NDEFReader()

    @State private var lastMark: Mark?
    @State private var status: String = ""
    @State private var statusKind: Kind = .waiting
    @State private var didComplete = false
    @State private var isSending = false
    @State private var showHistory = false
    @State private var pulse = false
    @State private var running = false

    private enum Kind { case waiting, success, error }

    var body: some View {
        VStack(spacing: 18) {
            topBar
            Spacer(minLength: 0)
            bigButton
            statusView
            Spacer(minLength: 0)
            bottomBar
        }
        .padding(20)
        .background(Color(.systemGroupedBackground))
        .sheet(isPresented: $showHistory) {
            HistoryView()
                .environmentObject(store)
                .environmentObject(settings)
        }
        .onAppear(perform: startOrKeep)
        .onDisappear {
            running = false
            ble.stop()
            nfc.cancel()
        }
    }

    // -------------------------------------------------------------- верх и низ

    private var topBar: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(auth.student?.name ?? settings.studentName)
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                Text(auth.student?.id ?? settings.studentId)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("Выйти") {
                ble.stop()
                auth.logout()
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
    }

    /// Единственная кнопка: приложить телефон (или повторить попытку).
    private var bigButton: some View {
        Button(action: restart) {
            ZStack {
                Circle()
                    .fill(ringColor.opacity(0.14))
                    .frame(width: 268, height: 268)
                    .scaleEffect(pulse ? 1.05 : 0.95)
                    .animation(
                        .easeInOut(duration: 1.1).repeatForever(autoreverses: true),
                        value: pulse
                    )

                Circle()
                    .strokeBorder(ringColor.opacity(0.45), lineWidth: 3)
                    .frame(width: 224, height: 224)

                VStack(spacing: 10) {
                    Image(systemName: iconName)
                        .font(.system(size: 64, weight: .semibold))
                        .foregroundStyle(ringColor)
                    Text(buttonLabel)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 18)
                }
            }
        }
        .buttonStyle(.plain)
        .onAppear { pulse = true }
        .disabled(isSending)   // пока отправляем — не даём сбить процесс; иначе кнопка = «повторить»
    }

    private var statusView: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(ringColor).frame(width: 9, height: 9)
                Text(status.isEmpty ? ble.statusText : status)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            if let rssi = ble.lastRssi {
                Text("сигнал \(rssi) dBm")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            if let mark = lastMark {
                Text("Последняя отметка: \(mark.subject) · \(timeText(mark.timestamp))"
                     + (mark.verified ? " · подтверждена" : ""))
                    .font(.caption)
                    .foregroundStyle(mark.verified ? Color.green : Color.orange)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 6)
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            if store.pendingCount > 0 {
                Button {
                    Task {
                        isSending = true
                        await SyncService.shared.syncPending(store: store, settings: settings)
                        if let mark = lastMark { lastMark = refreshed(mark) }
                        isSending = false
                    }
                } label: {
                    Label(isSending
                          ? "Отправляем…"
                          : "Отправить неотправленное (\(store.pendingCount))",
                          systemImage: "arrow.up.circle")
                        .font(.footnote)
                }
                .buttonStyle(.bordered)
                .disabled(isSending)
            }

            HStack(spacing: 16) {
                Button {
                    showHistory = true
                } label: {
                    Label("Мои отметки", systemImage: "list.bullet.rectangle")
                        .font(.footnote)
                }

                if NDEFReader.isAvailable {
                    Button {
                        startNfc()
                    } label: {
                        Label("Отметить по NFC", systemImage: "wave.3.forward.circle")
                            .font(.footnote)
                    }
                }
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.accentColor)

            if auth.offlineLogin {
                Text("Вход проверен без сервера (по сохранённому паролю)")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        }
    }

    // ------------------------------------------------------------------ внешний вид

    private var ringColor: Color {
        switch statusKind {
        case .success: return .green
        case .error: return .red
        case .waiting: break
        }
        switch ble.state {
        case .accepted: return .green
        case .rejected, .unavailable: return .red
        case .tooFar, .sending: return .orange
        default: return .accentColor
        }
    }

    private var iconName: String {
        switch statusKind {
        case .success: return "checkmark.circle.fill"
        case .error: return "exclamationmark.triangle.fill"
        case .waiting: break
        }
        switch ble.state {
        case .accepted: return "checkmark.circle.fill"
        case .tooFar: return "arrow.down.circle"
        case .rejected, .unavailable: return "exclamationmark.triangle.fill"
        case .sending, .connecting: return "wave.3.right"
        default: return "iphone.radiowaves.left.and.right"
        }
    }

    private var buttonLabel: String {
        switch statusKind {
        case .success: return "Вы отмечены"
        case .error: return "Повторить"
        case .waiting: break
        }
        switch ble.state {
        case .accepted: return "Вы отмечены"
        case .tooFar: return "Поднесите ближе"
        case .sending: return "Отправляем…"
        case .connecting: return "Проверяем расстояние…"
        case .unavailable: return "Bluetooth выключен"
        case .rejected: return "Повторить"
        case .searching, .idle: return "Приложить телефон"
        }
    }

    // ------------------------------------------------------------------- логика

    private func startOrKeep() {
        guard !running else { return }
        running = true
        restart()
    }

    /// Кнопка: заново запускаем поиск метки (и сбрасываем прошлый результат).
    private func restart() {
        didComplete = false
        status = ""
        statusKind = .waiting

        ble.onMark = handleMark(session:rssi:time:)
        ble.start(deviceId: DeviceIdentity.deviceId,
                  studentName: settings.studentName.isEmpty ? settings.studentId : settings.studentName)
    }

    private func startNfc() {
        nfc.onPayload = handleNfc(payload:)
        nfc.start()
        status = "Приложите iPhone к телефону преподавателя — читаем NFC-метку"
        statusKind = .waiting
    }

    /// Касание по Bluetooth: сохраняем отметку и сразу отправляем.
    private func handleMark(session: BleMarkClient.TerminalSession, rssi: Int, time: Date) {
        guard !didComplete else { return }
        didComplete = true
        nfc.cancel()

        let payload = SessionPayload(
            sessionId: session.sessionId,
            subject: session.subject,
            teacherId: session.teacherId,
            timestamp: time
        )
        save(payload: payload, source: "ble", rssi: rssi)
    }

    /// Касание по NFC (если доступно).
    private func handleNfc(payload: SessionPayload) {
        guard !didComplete else { return }
        didComplete = true
        ble.stop()
        save(payload: payload, source: "nfc", rssi: nil)
    }

    private func save(payload: SessionPayload, source: String, rssi: Int?) {
        let mark = store.addMark(payload: payload, settings: settings, source: source, rssi: rssi)
        store.saveStudent(id: mark.studentId, name: mark.studentName)
        lastMark = mark

        status = "Отметка сохранена: \(payload.subject). Отправляем на сервер…"
        statusKind = .waiting

        Task {
            await SyncService.shared.ensureSessionExists(payload: payload, settings: settings)
            await SyncService.shared.syncPending(store: store, settings: settings)

            let updated = refreshed(mark)
            lastMark = updated

            switch updated.syncState {
            case .sent where updated.verified:
                status = "Готово: \(payload.subject) в \(timeText(updated.timestamp))"
                statusKind = .success
            case .sent:
                status = "Отметка принята, но без подтверждения касанием — преподаватель её видит"
                statusKind = .waiting
            case .duplicate:
                status = updated.serverMessage.isEmpty ? "Вы уже отмечены в этой паре" : updated.serverMessage
                statusKind = .success
            case .rejected:
                status = updated.serverMessage.isEmpty ? "Сервер отклонил отметку" : updated.serverMessage
                statusKind = .error
            default:
                status = "Сервер недоступен. Отметка сохранена и уйдёт сама, когда появится связь."
                statusKind = .waiting
            }
        }
    }

    private func refreshed(_ mark: Mark) -> Mark {
        store.marks.first { $0.id == mark.id } ?? mark
    }

    private func timeText(_ date: Date) -> String {
        let text = SessionPayload.isoString(date)
        return String(text.suffix(8))
    }
}

#Preview {
    MarkView()
        .environmentObject(AttendanceStore.shared)
        .environmentObject(Settings.shared)
        .environmentObject(AuthService.shared)
}
