import Combine
import SwiftUI

/// Экран отметки: студент прикладывает iPhone к телефону преподавателя.
///
/// Работают два канала одновременно, и это скрыто от студента:
///   • Bluetooth (BLE) — основной: телефон находит метку терминала и «касается» её;
///   • NFC — если он доступен на устройстве (требует платного аккаунта Apple).
///
/// Что видит студент: «Приложите iPhone», через секунду — галочка и время.
struct MarkView: View {

    @EnvironmentObject private var store: AttendanceStore
    @EnvironmentObject private var settings: Settings

    @StateObject private var ble = BleMarkClient()
    @StateObject private var nfc = NDEFReader()

    @State private var banner: Banner?
    @State private var lastMark: Mark?
    @State private var isSending = false
    @State private var didComplete = false
    @State private var pulse = false
    @State private var sessionRunning = false

    private struct Banner: Equatable {
        let text: String
        let kind: Kind
        enum Kind: Equatable { case success, error, waiting }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    header
                    tapTarget
                    statusLine
                    if let banner {
                        bannerView(banner)
                    }
                    if !settings.isConfigured {
                        notConfiguredHint
                    }
                    if let mark = lastMark {
                        lastMarkCard(mark)
                    }
                    queueCard
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Отметиться")
            .onAppear(perform: startTapFlow)
            .onDisappear {
                stopTapFlow()
            }
        }
    }

    // ------------------------------------------------------------------- блоки

    private var header: some View {
        VStack(spacing: 6) {
            Text(settings.studentName.isEmpty ? "Студент" : settings.studentName)
                .font(.title2.weight(.bold))
            Text(settings.studentId.isEmpty ? "логин не указан" : settings.studentId)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    /// Круг-цель: пульсирует, пока приложение ищет терминал.
    private var tapTarget: some View {
        ZStack {
            Circle()
                .fill(accentColor.opacity(0.12))
                .frame(width: 210, height: 210)
                .scaleEffect(pulse ? 1.06 : 0.94)
                .animation(
                    .easeInOut(duration: 1.1).repeatForever(autoreverses: true),
                    value: pulse
                )

            Circle()
                .strokeBorder(accentColor.opacity(0.35), lineWidth: 2)
                .frame(width: 168, height: 168)

            VStack(spacing: 8) {
                Image(systemName: iconName)
                    .font(.system(size: 52, weight: .semibold))
                    .foregroundStyle(accentColor)
                Text(shortStatus)
                    .font(.footnote.weight(.medium))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
            }
        }
        .frame(maxWidth: .infinity)
        .onAppear { pulse = true }
    }

    private var statusLine: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(accentColor)
                .frame(width: 10, height: 10)
            Text(ble.statusText)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Spacer()
            if let rssi = ble.lastRssi {
                Text("\(rssi) dBm")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func bannerColor(_ kind: Banner.Kind) -> Color {
        switch kind {
        case .success: return .green
        case .error: return .red
        case .waiting: return .orange
        }
    }

    private func bannerIcon(_ kind: Banner.Kind) -> String {
        switch kind {
        case .success: return "checkmark.circle.fill"
        case .error: return "xmark.octagon.fill"
        case .waiting: return "hand.raised.fill"
        }
    }

    private func bannerView(_ banner: Banner) -> some View {
        let color: Color = bannerColor(banner.kind)
        let icon: String = bannerIcon(banner.kind)

        return HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
            Text(banner.text)
                .font(.subheadline)
            Spacer()
        }
        .padding(12)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .foregroundStyle(color)
    }

    private var notConfiguredHint: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Сначала настройте приложение", systemImage: "exclamationmark.triangle")
                .font(.subheadline.weight(.semibold))
            Text("Во вкладке «Настройки» укажите адрес ноутбука преподавателя "
                 + "(например, 192.168.1.10) и свой логин со вкладки «Студенты».")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func lastMarkCard(_ mark: Mark) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Последняя отметка")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            HStack {
                Text(mark.subject)
                    .font(.headline)
                Spacer()
                Text(SessionPayload.isoString(mark.timestamp).suffix(8))
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 6) {
                Image(systemName: mark.verified ? "checkmark.seal.fill" : "clock.badge.questionmark")
                    .foregroundStyle(mark.verified ? Color.green : Color.orange)
                Text(mark.serverMessage.isEmpty ? "Ждёт отправки" : mark.serverMessage)
                    .font(.footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var queueCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Очередь отправки")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(store.pendingCount == 0 ? "пусто" : "\(store.pendingCount) шт.")
                    .font(.footnote)
                    .foregroundStyle(store.pendingCount == 0 ? Color.secondary : Color.orange)
            }

            if !store.lastResult.isEmpty {
                Text(store.lastResult)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Button {
                Task {
                    isSending = true
                    await SyncService.shared.syncPending(store: store, settings: settings)
                    if let mark = lastMark { lastMark = refreshed(mark) }
                    isSending = false
                }
            } label: {
                Label(isSending ? "Отправляем…" : "Отправить неотправленное",
                      systemImage: "arrow.up.circle")
            }
            .buttonStyle(.bordered)
            .disabled(store.pendingCount == 0 || isSending)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    // ------------------------------------------------------------------ внешний вид

    private var accentColor: Color {
        switch ble.state {
        case .accepted: return .green
        case .rejected, .unavailable: return .red
        case .tooFar, .sending: return .orange
        default: return didComplete ? .green : .accentColor
        }
    }

    private var iconName: String {
        switch ble.state {
        case .accepted: return "checkmark.circle.fill"
        case .rejected, .unavailable: return "exclamationmark.triangle.fill"
        case .tooFar: return "arrow.down.circle"
        case .sending, .connecting: return "wave.3.right"
        default: return "iphone.radiowaves.left.and.right"
        }
    }

    private var shortStatus: String {
        switch ble.state {
        case .accepted: return "Вы отмечены"
        case .rejected(let text): return text
        case .unavailable(let text): return text
        case .tooFar: return "Поднесите телефон ближе"
        case .sending: return "Отправляем…"
        case .connecting: return "Проверяем расстояние…"
        case .searching: return "Приложите iPhone\nк телефону преподавателя"
        case .idle: return "Нажмите, чтобы отметить"
        }
    }

    // ------------------------------------------------------------------- логика

    /// Старт: одновременно слушаем BLE (основной канал) и NFC (если разрешён).
    private func startTapFlow() {
        guard !sessionRunning, settings.isConfigured else { return }
        sessionRunning = true
        didComplete = false
        banner = Banner(text: "Приложите iPhone к телефону преподавателя — отметка поставится сама.",
                        kind: .waiting)

        ble.onMark = handleBleMark(session:rssi:time:)
        ble.start(deviceId: DeviceIdentity.deviceId,
                  studentName: settings.studentName.isEmpty ? settings.studentId : settings.studentName)

        if NDEFReader.isAvailable {
            nfc.onPayload = handleNfcPayload(payload:)
            nfc.start()
        }
    }

    private func stopTapFlow() {
        sessionRunning = false
        ble.stop()
        nfc.cancel()
    }

    /// Касание по Bluetooth: отметка + отправка на сервер.
    private func handleBleMark(session: BleMarkClient.TerminalSession, rssi: Int, time: Date) {
        guard !didComplete else { return }
        didComplete = true
        nfc.cancel()

        let payload = SessionPayload(
            sessionId: session.sessionId,
            subject: session.subject,
            teacherId: session.teacherId,
            timestamp: time
        )
        saveAndSend(payload: payload, source: "ble", rssi: rssi)
    }

    /// Касание по NFC (если доступно).
    private func handleNfcPayload(payload: SessionPayload) {
        guard !didComplete else { return }
        didComplete = true
        ble.stop()
        saveAndSend(payload: payload, source: "nfc", rssi: nil)
    }

    private func saveAndSend(payload: SessionPayload, source: String, rssi: Int?) {
        let mark = store.addMark(payload: payload, settings: settings, source: source, rssi: rssi)
        store.saveStudent(id: mark.studentId, name: mark.studentName)
        lastMark = mark

        banner = Banner(text: "Отметка сохранена: \(payload.subject). Отправляем на сервер…",
                        kind: .waiting)

        Task {
            await SyncService.shared.ensureSessionExists(payload: payload, settings: settings)
            await SyncService.shared.syncPending(store: store, settings: settings)
            lastMark = refreshed(mark)

            switch mark.syncState {
            case .sent where mark.verified:
                banner = Banner(text: "Вы отмечены: \(payload.subject). "
                                      + "Сигнал \(rssi.map { "\($0) dBm" } ?? "—").",
                                kind: .success)
            case .sent:
                banner = Banner(text: "Отметка принята, но подтверждение касания слабое — "
                                      + "преподаватель увидит её как неподтверждённую.",
                                kind: .waiting)
            case .duplicate:
                banner = Banner(text: mark.serverMessage, kind: .success)
            case .rejected:
                banner = Banner(text: mark.serverMessage, kind: .error)
            default:
                banner = Banner(text: "Сервер недоступен. Отметка сохранена и уйдёт автоматически, "
                                      + "как только появится связь.", kind: .waiting)
            }
            sessionRunning = false
        }
    }

    private func refreshed(_ mark: Mark) -> Mark {
        store.marks.first { $0.id == mark.id } ?? mark
    }
}

#Preview {
    MarkView()
        .environmentObject(AttendanceStore.shared)
        .environmentObject(Settings.shared)
}
