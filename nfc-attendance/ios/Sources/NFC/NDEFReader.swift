import CoreNFC
import Foundation

/// Чтение NDEF-метки (режим Reader Mode).
///
/// iPhone не умеет эмулировать метку (HCE на iOS недоступна), зато умеет
/// читать: NFCNDEFReaderSession показывает системное окно «Приложите iPhone
/// к метке» и возвращает разобранное NDEF-сообщение.
final class NDEFReader: NSObject, ObservableObject {

    enum State: Equatable {
        case idle
        case scanning
        case success(SessionPayload)
        case failure(String)
    }

    @Published private(set) var state: State = .idle

    /// Вызывается на главном потоке после успешного чтения.
    var onPayload: ((SessionPayload) -> Void)?

    private var session: NFCNDEFReaderSession?

    /// Доступно ли чтение NFC на этом устройстве.
    static var isAvailable: Bool {
        NFCNDEFReaderSession.readingAvailable
    }

    /// Запустить сканирование (системное окно закроется само после первого чтения).
    func start() {
        guard NDEFReader.isAvailable else {
            state = .failure("На этом iPhone чтение NFC недоступно (или выключено в Настройках).")
            return
        }
        guard session == nil else { return }

        state = .scanning
        let session = NFCNDEFReaderSession(delegate: self, queue: nil, invalidateAfterFirstRead: true)
        session.alertMessage = "Приложите iPhone к телефону преподавателя"
        self.session = session
        session.begin()
    }

    func cancel() {
        session?.invalidate()
        session = nil
        if case .scanning = state { state = .idle }
    }

    // ------------------------------------------------------ NFCNDEFReaderSessionDelegate

    func readerSessionDidBecomeActive(_ session: NFCNDEFReaderSession) {
        // окно сканирования открылось
    }

    func readerSession(_ session: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {
        guard let payload = decode(messages) else {
            session.invalidate(errorMessage: "Это не метка пары КубГАУ. Попробуйте ещё раз.")
            finish(state: .failure("В метке нет данных пары"))
            return
        }

        session.alertMessage = "Отметка получена"
        finish(state: .success(payload))
    }

    func readerSession(_ session: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        self.session = nil

        if let nfcError = error as? NFCReaderError {
            switch nfcError.code {
            case .readerSessionInvalidationErrorFirstNDEFTagRead,
                 .readerSessionInvalidationErrorUserCanceled:
                // нормальное завершение: метка прочитана или пользователь закрыл окно
                if case .success = state { return }
                state = .idle
                return
            case .readerSessionInvalidationErrorSessionTimeout:
                state = .failure("Не успели приложить телефон. Нажмите «Отметиться» ещё раз.")
                return
            default:
                break
            }
        }

        if case .scanning = state {
            state = .failure(error.localizedDescription)
        }
    }

    // ------------------------------------------------------------------ разбор

    private func decode(_ messages: [NFCNDEFMessage]) -> SessionPayload? {
        for message in messages {
            for record in message.records {
                let type = String(data: record.type, encoding: .utf8)?
                    .trimmingCharacters(in: .controlCharacters)
                if let payload = SessionPayload.parse(recordType: type, payload: record.payload) {
                    return payload
                }
            }
        }
        return nil
    }

    private func finish(state: State) {
        self.session = nil
        DispatchQueue.main.async {
            self.state = state
            if case .success(let payload) = state {
                self.onPayload?(payload)
            }
        }
    }
}
