import SwiftUI
import CoreNFC
import UIKit

// MARK: - константы

private let aid: [UInt8] = [0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04]
private let prefs = UserDefaults.standard
private let keyLogin = "login"
private let keyPassword = "password"
private let keyToken = "token"

// MARK: - модель NFC

final class MarkModel: NSObject, ObservableObject, NFCTagReaderSessionDelegate {

    @Published var status = ""

    private var session: NFCTagReaderSession?

    func start() {
        guard NFCTagReaderSession.readingAvailable else {
            status = "NFC на этом iPhone недоступен"
            return
        }
        status = ""
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: nil)
        session?.alertMessage = "Приложите iPhone к телефону преподавателя"
        session?.begin()
    }

    // MARK: NFCTagReaderSessionDelegate

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        DispatchQueue.main.async {
            if (error as? NFCReaderError)?.code == .readerSessionInvalidationErrorUserCanceled {
                self.status = "Отменено"
            }
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard case .iso7816 = tags.first else {
            session.restartPolling()
            return
        }
        session.connect(to: tags.first!) { [weak self] error in
            guard let self, error == nil, case .iso7816(let tag) = tags.first else {
                session.invalidate(errorMessage: "Не получилось, попробуйте ещё раз")
                return
            }
            self.handle(tag: tag, session: session)
        }
    }

    // MARK: обмен APDU

    private func handle(tag: NFCISO7816Tag, session: NFCTagReaderSession) {
        let select = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0xA4,
                                     p1Parameter: 0x04, p2Parameter: 0x00,
                                     data: Data(aid), expectedResponseLength: 2)
        tag.sendCommand(commandAPDU: select) { [weak self] _, statusWord, error in
            guard let self, error == nil, statusWord == 0x9000 else {
                session.invalidate(errorMessage: "Это не телефон преподавателя")
                return
            }
            self.sendIdentity(tag: tag, session: session, allowRelogin: true)
        }
    }

    private func sendIdentity(tag: NFCISO7816Tag, session: NFCTagReaderSession, allowRelogin: Bool) {
        guard let login = prefs.string(forKey: keyLogin),
              let password = prefs.string(forKey: keyPassword) else {
            session.invalidate(errorMessage: "Сначала войдите по логину и паролю")
            return
        }

        var type: UInt8 = 1
        var payload = login + "\n" + password
        if let token = prefs.string(forKey: keyToken), !token.isEmpty {
            type = 2
            payload = token
        }
        let command = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0x10,
                                      p1Parameter: 0x00, p2Parameter: 0x00,
                                      data: Data([type] + Array(payload.utf8)),
                                      expectedResponseLength: 256)
        tag.sendCommand(commandAPDU: command) { [weak self] data, statusWord, error in
            guard let self, error == nil, statusWord == 0x9000, !data.isEmpty else {
                session.invalidate(errorMessage: "Не получилось, попробуйте ещё раз")
                return
            }
            let result = data[data.startIndex]
            let value = String(data: data.dropFirst(), encoding: .utf8) ?? ""

            switch result {
            case 0 where !value.isEmpty:
                prefs.set(value, forKey: keyToken)
                self.finish(session: session, message: "Готово! Вы отмечены")
            case 1:
                self.finish(session: session,
                            message: value.isEmpty ? "Вы отмечены" : "Вы отмечены — \(value)")
            case 2:
                DispatchQueue.main.async { self.status = "Неверный логин или пароль" }
                session.invalidate(errorMessage: "Неверный логин или пароль")
            case 3 where allowRelogin:
                // сессия устарела — этим же касанием перелогинимся
                prefs.removeObject(forKey: keyToken)
                self.sendIdentity(tag: tag, session: session, allowRelogin: false)
            default:
                session.invalidate(errorMessage: "Не получилось, попробуйте ещё раз")
            }
        }
    }

    private func finish(session: NFCTagReaderSession, message: String) {
        DispatchQueue.main.async {
            self.status = message
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
        session.alertMessage = message
        session.invalidate()
    }
}

// MARK: - интерфейс

struct ContentView: View {
    @StateObject private var nfc = MarkModel()
    @State private var showLogin = false

    private var loggedIn: Bool {
        (prefs.string(forKey: keyLogin) ?? "").isEmpty == false
    }

    var body: some View {
        VStack(spacing: 18) {
            if showLogin || !loggedIn {
                LoginView(done: { showLogin = false })
            } else {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 84))
                    .foregroundColor(Color(red: 0.29, green: 0.78, blue: 0.42))
                Text("Visits11")
                    .font(.title.bold())
                Text(nfc.status.isEmpty ? "Отметка на паре" : nfc.status)
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                    .animation(.easeInOut, value: nfc.status)

                Button {
                    nfc.start()
                } label: {
                    Text("Отметиться")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(red: 0.29, green: 0.78, blue: 0.42))
                .padding(.top, 8)

                Button("Сменить студента") {
                    showLogin = true
                }
                .font(.footnote)
            }
        }
        .padding(24)
    }
}

struct LoginView: View {
    var done: () -> Void

    @State private var login = ""
    @State private var password = ""

    var body: some View {
        VStack(spacing: 14) {
            Text("Вход")
                .font(.title.bold())
            TextField("Логин", text: $login)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)
                .disableAutocorrection(true)
            SecureField("Пароль", text: $password)
                .textFieldStyle(.roundedBorder)
            Button {
                guard !login.trimmingCharacters(in: .whitespaces).isEmpty, !password.isEmpty else { return }
                prefs.set(login.trimmingCharacters(in: .whitespaces), forKey: keyLogin)
                prefs.set(password, forKey: keyPassword)
                prefs.removeObject(forKey: keyToken)
                done()
            } label: {
                Text("Войти")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 8)
        }
    }
}
