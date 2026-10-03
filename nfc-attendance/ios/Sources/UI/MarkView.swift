import SwiftUI

/// Главный экран: кнопка «Отметиться» и результат последней отметки.
///
/// Порядок действий:
///   1. нажимаем «Отметиться» → открывается окно CoreNFC;
///   2. прикладываем iPhone к телефону преподавателя;
///   3. сохраняем отметку в Core Data и сразу отправляем на сервер;
///   4. если сети нет — отметка останется в очереди и уйдёт позже.
struct MarkView: View {

    @EnvironmentObject private var store: AttendanceStore
    @EnvironmentObject private var settings: Settings

    @StateObject private var reader = NDEFReader()
    @State private var banner: Banner?
    @State private var lastMark: Mark?
    @State private var isSending = false

    private struct Banner: Equatable {
        let text: String
        let isError: Bool
        let isSuccess: Bool
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    header
                    nfcStatusCard

                    Button(action: startReading) {
                        HStack(spacing: 10) {
                            Image(systemName: "dot.radiowaves.left.and.right")
                            Text(reader.state == .scanning ? "Приложите iPhone…" : "Отметиться")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!settings.isConfigured || reader.state == .scanning)

                    if let banner {
                        bannerView(banner)
                    }

                    if !settings.isConfigured {
                        notConfiguredHint
                    }

                    if let mark = lastMark {
                        lastMarkCard(mark)
                    }

                    syncCard
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Отметиться")
            .onAppear {
                reader.onPayload = handle(payload:)
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

    private var nfcStatusCard: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(NDEFReader.isAvailable ? Color.green : Color.red)
                .frame(width: 10, height: 10)
            Text(NDEFReader.isAvailable
                 ? "NFC готово: приложите iPhone к телефону преподавателя"
                 : "Чтение NFC недоступно — включите NFC в Настройках iPhone")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
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

    private func bannerView(_ banner: Banner) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: banner.isSuccess ? "checkmark.circle.fill"
                  : (banner.isError ? "xmark.octagon.fill" : "clock.fill"))
            Text(banner.text)
                .font(.subheadline)
            Spacer()
        }
        .padding(12)
        .background(
            (banner.isSuccess ? Color.green : (banner.isError ? Color.red : Color.orange))
                .opacity(0.12),
            in: RoundedRectangle(cornerRadius: 12),
        )
        .foregroundStyle(banner.isSuccess ? Color.green : (banner.isError ? Color.red : Color.orange))
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

            Text("Пара: \(mark.sessionId.prefix(8))…")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 6) {
                Image(systemName: mark.verified ? "checkmark.seal.fill" : "questionmark.circle")
                    .foregroundStyle(mark.verified ? Color.green : Color.orange)
                Text(mark.serverMessage.isEmpty ? "Ждёт отправки" : mark.serverMessage)
                    .font(.footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private var syncCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Очередь отправки")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(store.pendingCount == 0 ? "пусто" : "\(store.pendingCount) шт.")
                    .font(.footnote)
                    .foregroundStyle(store.pendingCount == 0 ? .secondary : .orange)
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

    // ------------------------------------------------------------------- логика

    private func startReading() {
        banner = nil
        reader.start()
    }

    /// Метка прочитана: сохраняем отметку и отправляем на сервер.
    private func handle(payload: SessionPayload) {
        guard settings.isConfigured else {
            banner = Banner(text: "Заполните адрес сервера и логин во вкладке «Настройки».",
                            isError: true, isSuccess: false)
            return
        }
        guard payload.isFresh() else {
            banner = Banner(text: "Метка устарела (время в ней не совпадает с текущим). "
                                  + "Попросите преподавателя начать пару заново.",
                            isError: true, isSuccess: false)
            return
        }

        let mark = store.addMark(payload: payload, settings: settings)
        store.saveStudent(id: mark.studentId, name: mark.studentName)
        lastMark = mark
        banner = Banner(text: "Отметка сохранена: \(payload.subject). Отправляем на сервер…",
                        isError: false, isSuccess: true)

        Task {
            await SyncService.shared.ensureSessionExists(payload: payload, settings: settings)
            await SyncService.shared.syncPending(store: store, settings: settings)
            lastMark = refreshed(mark)

            if mark.syncState == .sent {
                banner = Banner(
                    text: mark.verified
                        ? "Вы отмечены: \(payload.subject)"
                        : "Отметка принята. Касание телефона преподавателя не подтвердилось — "
                          + "преподаватель увидит её как неподтверждённую.",
                    isError: false, isSuccess: true,
                )
            } else if mark.syncState == .duplicate {
                banner = Banner(text: mark.serverMessage, isError: false, isSuccess: true)
            } else if mark.syncState == .rejected {
                banner = Banner(text: mark.serverMessage, isError: true, isSuccess: false)
            } else {
                banner = Banner(text: "Сервер недоступен. Отметка сохранена и уйдёт автоматически, "
                                      + "как только появится связь.", isError: false, isSuccess: false)
            }
        }
    }

    /// Перечитывает актуальную версию отметки из хранилища.
    private func refreshed(_ mark: Mark) -> Mark {
        store.marks.first { $0.id == mark.id } ?? mark
    }
}

#Preview {
    MarkView()
        .environmentObject(AttendanceStore.shared)
        .environmentObject(Settings.shared)
}
