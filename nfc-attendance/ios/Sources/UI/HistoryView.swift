import SwiftUI

/// История отметок: что уже сохранено на телефоне и что с этим на сервере.
struct HistoryView: View {

    @EnvironmentObject private var store: AttendanceStore
    @EnvironmentObject private var settings: Settings

    @State private var selected: Mark?
    @State private var present: [ApiClient.PresentRow] = []
    @State private var isLoadingPresent = false

    var body: some View {
        NavigationStack {
            List {
                if store.marks.isEmpty {
                    Text("Отметок пока нет. Нажмите «Отметиться» и приложите iPhone к телефону преподавателя.")
                        .foregroundStyle(.secondary)
                }

                ForEach(store.marks, id: \.id) { mark in
                    Button {
                        selected = mark
                        loadPresent(for: mark)
                    } label: {
                        row(mark)
                    }
                    .buttonStyle(.plain)
                }
                .onDelete { indexSet in
                    indexSet.map { store.marks[$0] }.forEach(store.delete)
                }
            }
            .navigationTitle("Мои отметки")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Task { await SyncService.shared.syncPending(store: store, settings: settings) }
                    } label: {
                        Label("Отправить", systemImage: "arrow.up.circle")
                    }
                    .disabled(store.pendingCount == 0)
                }
            }
            .sheet(item: $selected) { mark in
                details(mark)
                    .presentationDetents([.medium, .large])
            }
        }
    }

    // ------------------------------------------------------------------- строки

    private func row(_ mark: Mark) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(mark.subject)
                    .font(.headline)
                Spacer()
                stateBadge(mark)
            }
            Text(SessionPayload.isoString(mark.timestamp).replacingOccurrences(of: "T", with: " "))
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text("Пара \(mark.sessionId.prefix(8))… · препод. \(mark.teacherId)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func stateBadge(_ mark: Mark) -> some View {
        let (text, color): (String, Color) = {
            switch mark.syncState {
            case .pending: return ("в очереди", .orange)
            case .sent: return (mark.verified ? "подтверждено" : "принято", .green)
            case .duplicate: return ("уже было", .blue)
            case .rejected: return ("отклонено", .red)
            }
        }()
        return Text(text)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
    }

    // ------------------------------------------------------------------ детали

    private func details(_ mark: Mark) -> some View {
        NavigationStack {
            List {
                Section("Отметка") {
                    labeled("Предмет", mark.subject)
                    labeled("Время", SessionPayload.isoString(mark.timestamp))
                    labeled("Пара", mark.sessionId)
                    labeled("Преподаватель", mark.teacherId)
                    labeled("Студент", "\(mark.studentName) (\(mark.studentId))")
                    labeled("Телефон", mark.deviceId)
                    labeled("Состояние", mark.serverMessage.isEmpty ? "ожидает" : mark.serverMessage)
                    labeled("Подтверждено касанием", mark.verified ? "да" : "нет")
                }

                Section("Кто ещё отметился на паре") {
                    if isLoadingPresent {
                        ProgressView()
                    } else if present.isEmpty {
                        Text("Сервер недоступен или список пуст")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(present) { row in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(row.studentName ?? row.studentId)
                                    Text(row.studentId)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if row.verified == 1 {
                                    Image(systemName: "checkmark.seal.fill")
                                        .foregroundStyle(.green)
                                }
                            }
                        }
                    }
                }

                Section {
                    Button(role: .destructive) {
                        store.delete(mark)
                        selected = nil
                    } label: {
                        Label("Удалить отметку", systemImage: "trash")
                    }
                }
            }
            .navigationTitle("Детали")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Закрыть") {
                        selected = nil
                    }
                }
            }
        }
    }

    private func labeled(_ title: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(title)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .multilineTextAlignment(.trailing)
        }
        .font(.footnote)
    }

    private func loadPresent(for mark: Mark) {
        isLoadingPresent = true
        present = []
        Task {
            present = await SyncService.shared.present(sessionId: mark.sessionId, settings: settings)
            isLoadingPresent = false
        }
    }
}

#Preview {
    HistoryView()
        .environmentObject(AttendanceStore.shared)
        .environmentObject(Settings.shared)
}
