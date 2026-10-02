import CoreNFC
import UIKit

final class NfcReader: NSObject, NFCTagReaderSessionDelegate {
    enum Event {
        case status(String)
        case loginRejected
        case unavailable
    }

    var onEvent: ((Event) -> Void)?

    private static let aid: [UInt8] = [0xF0, 0x39, 0x11, 0x01, 0x02, 0x03, 0x04]
    private var session: NFCTagReaderSession?

    static var supported: Bool {
        NFCTagReaderSession.readingAvailable
    }

    func begin() {
        guard NfcReader.supported else {
            emit(.unavailable)
            return
        }
        session?.invalidate()
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: nil)
        session?.alertMessage = "Приложите телефон к телефону преподавателя"
        session?.begin()
    }

    private func emit(_ event: Event) {
        DispatchQueue.main.async { [weak self] in
            self?.onEvent?(event)
        }
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        guard let nfcError = error as? NFCReaderError else { return }
        switch nfcError.code {
        case .readerErrorSecurityViolation, .readerErrorUnsupportedFeature:
            emit(.unavailable)
        default:
            break
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let first = tags.first, case .iso7816(let tag) = first else {
            session.restartPolling()
            return
        }
        Task {
            do {
                try await session.connect(to: first)
                await self.exchange(tag: tag, session: session)
            } catch {
                self.fail(session, "Не получилось — приложите телефон еще раз")
            }
        }
    }

    private func fail(_ session: NFCTagReaderSession, _ message: String) {
        emit(.status(message))
        session.invalidate(errorMessage: message)
    }

    private func succeed(_ session: NFCTagReaderSession, _ message: String) {
        emit(.status(message))
        DispatchQueue.main.async {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
        session.alertMessage = message
        session.invalidate()
    }

    private func command(type: UInt8, text: String) -> NFCISO7816APDU {
        NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0x10,
                       p1Parameter: 0x00, p2Parameter: 0x00,
                       data: Data([type] + Array(text.utf8)),
                       expectedResponseLength: 256)
    }

    private func exchange(tag: NFCISO7816Tag, session: NFCTagReaderSession) async {
        do {
            let select = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0xA4,
                                        p1Parameter: 0x04, p2Parameter: 0x00,
                                        data: Data(NfcReader.aid), expectedResponseLength: 256)
            let (_, selectSw1, selectSw2) = try await tag.sendCommand(apdu: select)
            guard selectSw1 == 0x90, selectSw2 == 0x00 else {
                fail(session, "Не получилось — приложите телефон еще раз")
                return
            }

            guard let login = Store.login, let password = Store.password else {
                session.invalidate()
                return
            }

            let device = Store.deviceId()
            for _ in 0..<2 {
                let request: NFCISO7816APDU
                if let token = Store.token, !token.isEmpty {
                    request = command(type: 2, text: token + "\n" + device)
                } else {
                    request = command(type: 1, text: login + "\n" + password + "\n" + device)
                }

                let (data, sw1, sw2) = try await tag.sendCommand(apdu: request)
                guard sw1 == 0x90, sw2 == 0x00, !data.isEmpty else {
                    fail(session, "Не получилось, попробуйте ещё раз")
                    return
                }

                let result = data[data.startIndex]
                let value = String(data: data.dropFirst(), encoding: .utf8) ?? ""

                if result == 0 && !value.isEmpty {
                    Store.token = value
                    succeed(session, "Готово! Вы отмечены")
                    return
                }
                if result == 1 {
                    succeed(session, value.isEmpty ? "Вы отмечены" : "Вы отмечены — \(value)")
                    return
                }
                if result == 3 {
                    Store.token = nil
                    continue
                }
                if result == 5 {
                    fail(session, "Аккаунт привязан к другому телефону")
                    return
                }
                if result == 2 {
                    emit(.loginRejected)
                    session.invalidate(errorMessage: "Неверный логин или пароль")
                    return
                }
                fail(session, "Не получилось, попробуйте ещё раз")
                return
            }
            fail(session, "Не получилось, попробуйте ещё раз")
        } catch {
            fail(session, "Не получилось, попробуйте ещё раз")
        }
    }
}
