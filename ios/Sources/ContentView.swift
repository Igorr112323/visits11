import SwiftUI
import CoreNFC
import UIKit

// MARK: - константы

private let aid: [UInt8] = [0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04]
private let prefs = UserDefaults.standard
private let keyToken = "token"   // код студента из QR-кода — заменяет логин и пароль
private let keyName = "name"     // ФИО студента (показываем на экране)
private let keyDevice = "device" // id устройства: аккаунт привязывается к телефону

private let green = Color(red: 0.29, green: 0.78, blue: 0.42)

/// Уникальный ID устройства — аккаунт привязывается к первому телефону.
private func deviceId() -> String {
    if let saved = prefs.string(forKey: keyDevice), !saved.isEmpty {
        return saved
    }
    let id = UUID().uuidString
    prefs.set(id, forKey: keyDevice)
    return id
}

// MARK: - модель NFC

final class MarkModel: NSObject, ObservableObject, NFCTagReaderSessionDelegate {

    /// Результат последнего касания («Вы отмечены — Иванов И. И.»).
    @Published var status = ""

    /// QR-код не найден в журнале — нужно войти заново.
    @Published var needsLogin = false

    private var session: NFCTagReaderSession?

    func start() {
        guard NFCTagReaderSession.readingAvailable else {
            status = "NFC на этом iPhone недоступен"
            return
        }
        guard let token = prefs.string(forKey: keyToken), !token.isEmpty else {
            needsLogin = true
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
        tag.sendCommand(apdu: select) { [weak self] _, sw1, sw2, error in
            guard let self, error == nil, sw1 == 0x90, sw2 == 0x00 else {
                session.invalidate(errorMessage: "Это не телефон преподавателя")
                return
            }
            self.sendToken(tag: tag, session: session)
        }
    }

    /// Отправляет код студента и id устройства — логина и пароля больше нет.
    private func sendToken(tag: NFCISO7816Tag, session: NFCTagReaderSession) {
        guard let token = prefs.string(forKey: keyToken), !token.isEmpty else {
            session.invalidate(errorMessage: "Сначала войдите по QR-коду")
            return
        }

        let payload = token + "\n" + deviceId()
        let command = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0x10,
                                     p1Parameter: 0x00, p2Parameter: 0x00,
                                     data: Data(payload.utf8),
                                     expectedResponseLength: 256)
        tag.sendCommand(apdu: command) { [weak self] data, sw1, sw2, error in
            guard let self, error == nil, sw1 == 0x90, sw2 == 0x00, !data.isEmpty else {
                session.invalidate(errorMessage: "Не получилось, попробуйте ещё раз")
                return
            }
            let result = data[data.startIndex]
            let value = String(data: data.dropFirst(), encoding: .utf8) ?? ""

            switch result {
            case 0:
                // отмечен: дальше имя студента
                self.finish(session: session,
                            message: value.isEmpty ? "Вы отмечены" : "Вы отмечены — \(value)")
            case 2:
                // кода нет в журнале: стираем его и просим войти заново
                DispatchQueue.main.async {
                    prefs.removeObject(forKey: keyToken)
                    self.needsLogin = true
                }
                session.invalidate(errorMessage: "Такого QR-кода нет в журнале — войдите заново")
            case 5:
                DispatchQueue.main.async { self.status = "Этот телефон привязан к другому студенту" }
                session.invalidate(errorMessage: "Этот телефон привязан к другому студенту")
            default:
                DispatchQueue.main.async { self.status = "Не получилось: перекличка не идёт" }
                session.invalidate(errorMessage: "Не получилось: перекличка не идёт")
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
    @AppStorage(keyToken) private var savedToken = ""
    @AppStorage(keyName) private var studentName = ""

    private var loggedIn: Bool {
        !savedToken.isEmpty
    }

    var body: some View {
        VStack(spacing: 18) {
            if showLogin || !loggedIn || nfc.needsLogin {
                LoginView(done: {
                    showLogin = false
                    nfc.needsLogin = false
                    nfc.status = ""
                })
            } else {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 84))
                    .foregroundColor(green)
                Text("Visits11")
                    .font(.title.bold())
                if !studentName.isEmpty {
                    Text(studentName)
                        .font(.headline)
                }
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
                .tint(green)
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

/// Вход: логина и пароля нет — только QR-код (камерой или из галереи).
struct LoginView: View {
    var done: () -> Void

    @State private var showQrPicker = false
    @State private var showCamera = false
    @State private var qrMessage: String?

    var body: some View {
        VStack(spacing: 14) {
            Text("Вход")
                .font(.title.bold())
            Text("Отсканируйте свой QR-код или выберите картинку из галереи")
                .font(.footnote)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            Button {
                qrMessage = nil
                showCamera = true
            } label: {
                Text("Сканировать QR-код")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.borderedProminent)
            .tint(green)
            .padding(.top, 8)

            Button {
                qrMessage = nil
                showQrPicker = true
            } label: {
                Text("Загрузить из галереи")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)

            if let qrMessage {
                Text(qrMessage)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .sheet(isPresented: $showQrPicker) {
            PhotoPicker(onPick: importImage, onFinish: { showQrPicker = false })
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraScanner(onCode: importCode, onCancel: { showCamera = false })
        }
    }

    /// Код, распознанный камерой.
    private func importCode(_ text: String) {
        showCamera = false
        DispatchQueue.main.async {
            apply(text)
        }
    }

    /// Картинка из галереи: распознаём в фоне, чтобы не подвешивать интерфейс.
    private func importImage(_ image: UIImage) {
        qrMessage = "Читаю QR-код…"
        DispatchQueue.global(qos: .userInitiated).async {
            let text = QrImageDecoder.text(from: image)
            DispatchQueue.main.async { apply(text) }
        }
    }

    /// Общий шаг для обоих способов: сохраняем код студента — это и есть вход.
    private func apply(_ text: String?) {
        guard let text, let credentials = StudentQrCredentials.parse(text) else {
            qrMessage = "В этой картинке нет кода для входа"
            return
        }
        prefs.set(credentials.token, forKey: keyToken)
        prefs.set(credentials.name, forKey: keyName)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        done()
    }
}
