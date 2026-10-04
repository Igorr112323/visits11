import SwiftUI

/// Единственный экран входа — как в Android-приложении: минимум полей и одна кнопка.
///
/// Что здесь есть:
///   • адрес сервера преподавателя;
///   • логин и пароль;
///   • одна кнопка: «Войти» (или «Создать аккаунт», если логина ещё нет);
///   • ФИО появляется только при создании аккаунта.
///
/// Проверяет пароль **сервер**: приложение отправляет логин и пароль на
/// `POST /api/students/login` и показывает ответ (неверный пароль, нет логина
/// и т. д.). Если сервер недоступен, а студент уже входил с этого iPhone —
/// пароль сверяется с сохранённым хэшем (офлайн-вход).
struct LoginView: View {

    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var auth: AuthService

    @State private var server: String = ""
    @State private var studentId: String = ""
    @State private var password: String = ""
    @State private var fullName: String = ""
    @State private var needsAccount = false
    @State private var message: String = ""
    @State private var messageIsError = false
    @State private var busy = false
    @FocusState private var focus: Field?

    private enum Field { case server, login, password, name }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                card
                actionButton
                if !message.isEmpty {
                    messageView
                }
                hint
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground))
        .scrollDismissesKeyboard(.interactively)
        .onAppear {
            server = settings.serverURL
            studentId = settings.studentId
            message = ""
        }
    }

    // ------------------------------------------------------------------- блоки

    private var header: some View {
        VStack(spacing: 10) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 54))
                .foregroundStyle(.green)
            Text("КубГАУ · Отметки")
                .font(.title2.weight(.bold))
            Text("Войдите один раз — дальше одна кнопка")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 12)
    }

    private var card: some View {
        VStack(spacing: 0) {
            field(title: "Сервер преподавателя", systemImage: "wifi") {
                TextField("192.168.1.10", text: $server)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .server)
                    .onSubmit { focus = .login }
            }

            Divider()

            field(title: "Логин", systemImage: "person") {
                TextField("например ARHIPOV_II", text: $studentId)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .login)
                    .onSubmit { focus = .password }
            }

            Divider()

            field(title: "Пароль", systemImage: "lock") {
                SecureField("пароль", text: $password)
                    .textContentType(.password)
                    .focused($focus, equals: .password)
                    .onSubmit { submit() }
            }

            if needsAccount {
                Divider()
                field(title: "ФИО", systemImage: "person.text.rectangle") {
                    TextField("Иванов Иван Иванович", text: $fullName)
                        .focused($focus, equals: .name)
                        .onSubmit { submit() }
                }
            }
        }
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func field<Content: View>(
        title: String,
        systemImage: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .frame(width: 24)
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                content()
                    .font(.body)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    /// Та самая единственная кнопка.
    private var actionButton: some View {
        Button(action: submit) {
            HStack(spacing: 10) {
                if busy {
                    ProgressView().tint(.white)
                } else {
                    Image(systemName: needsAccount ? "person.badge.plus" : "arrow.right.circle.fill")
                }
                Text(busy ? "Проверяем…" : (needsAccount ? "Создать аккаунт" : "Войти"))
                    .font(.headline)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(Color.green, in: RoundedRectangle(cornerRadius: 16))
            .foregroundStyle(.white)
        }
        .disabled(busy)
        .opacity(busy ? 0.7 : 1)
    }

    private var messageView: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: messageIsError ? "exclamationmark.triangle.fill" : "info.circle.fill")
            Text(message).font(.footnote)
            Spacer()
        }
        .padding(12)
        .background(
            (messageIsError ? Color.red : Color.orange).opacity(0.12),
            in: RoundedRectangle(cornerRadius: 12)
        )
        .foregroundStyle(messageIsError ? Color.red : Color.orange)
    }

    private var hint: some View {
        Text("Адрес сервера показывает преподаватель: он запускает сервер на ноутбуке, "
             + "и в окне печатается адрес вида 192.168.1.10:8000.")
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 8)
    }

    // ------------------------------------------------------------------ логика

    private func submit() {
        let id = studentId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, !password.isEmpty else {
            show(message: "Введите логин и пароль", isError: true)
            return
        }
        settings.serverURL = server.trimmingCharacters(in: .whitespacesAndNewlines)

        busy = true
        focus = nil

        Task {
            let outcome: AuthService.Outcome
            if needsAccount {
                outcome = await auth.register(studentId: id, name: fullName, password: password)
            } else {
                outcome = await auth.login(studentId: id, password: password)
            }

            busy = false

            switch outcome {
            case .ok(let offline):
                show(message: offline
                     ? "Сервер недоступен — вход проверен по сохранённому паролю. Отметки уйдут позже."
                     : "",
                     isError: false)

            case .wrongPassword(let text):
                show(message: text, isError: true)

            case .needsAccount(let text):
                needsAccount = true
                show(message: text + " Введите ФИО и нажмите «Создать аккаунт».", isError: false)
                focus = .name

            case .failed(let text):
                show(message: text, isError: true)
            }
        }
    }

    private func show(message text: String, isError: Bool) {
        message = text
        messageIsError = isError
    }
}

#Preview {
    LoginView()
        .environmentObject(Settings.shared)
        .environmentObject(AuthService.shared)
}
