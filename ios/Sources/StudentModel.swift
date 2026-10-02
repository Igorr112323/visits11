import SwiftUI
import UIKit

@MainActor
final class StudentModel: ObservableObject {
    @Published var statusText: String?
    @Published var qrImage: UIImage?
    @Published var loginVisible = false
    @Published var dotVisible = false
    @Published var dotGreen = false
    @Published var toastText: String?

    private var useNfc: Bool
    private let nfc = NfcReader()
    private var loopTask: Task<Void, Never>?
    private var host: String?
    private var token: String?
    private var toastTask: Task<Void, Never>?

    private static let waitingText = "Нажмите здесь и приложите телефон к телефону преподавателя"

    init() {
        useNfc = NfcReader.supported
        nfc.onEvent = { [weak self] event in
            Task { @MainActor in
                self?.handle(event)
            }
        }
    }

    func start(auto: Bool) {
        UIApplication.shared.isIdleTimerDisabled = true
        if useNfc {
            dotVisible = false
            refreshNfcStatus()
            if auto && Store.login != nil {
                beginNfc()
            }
        } else {
            UIScreen.main.brightness = 1
            startLoop()
        }
    }

    func stop() {
        loopTask?.cancel()
        loopTask = nil
    }

    func tapStatus() {
        if useNfc {
            beginNfc()
        }
    }

    func longPressStatus() {
        loginVisible = true
    }

    func longPressQr() {
        loginVisible = true
        qrImage = nil
        statusText = "Войдите по логину и паролю"
    }

    func submitLogin(login: String, password: String) {
        let trimmed = login.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !password.isEmpty else {
            toast("Введите логин и пароль")
            return
        }
        Store.login = trimmed
        Store.password = password
        Store.token = nil
        token = nil
        if useNfc {
            refreshNfcStatus()
            beginNfc()
        } else {
            loopTask?.cancel()
            loopTask = nil
            startLoop()
        }
    }

    private func refreshNfcStatus() {
        if Store.login != nil {
            statusText = StudentModel.waitingText
            qrImage = nil
            loginVisible = false
        } else {
            statusText = nil
            loginVisible = true
        }
    }

    private func beginNfc() {
        guard useNfc, Store.login != nil else { return }
        statusText = StudentModel.waitingText
        nfc.begin()
    }

    private func handle(_ event: NfcReader.Event) {
        switch event {
        case .status(let text):
            statusText = text
        case .loginRejected:
            statusText = "НЕВЕРНЫЙ ЛОГИН ИЛИ ПАРОЛЬ — введите другие и приложите телефон ещё раз"
            loginVisible = true
        case .unavailable:
            guard useNfc else { return }
            useNfc = false
            toast("NFC недоступен в этой сборке — включён режим Wi-Fi")
            UIScreen.main.brightness = 1
            startLoop()
        }
    }

    private func startLoop() {
        guard loopTask == nil else { return }
        dotVisible = true
        loopTask = Task { [weak self] in
            await self?.runLoop()
        }
    }

    private func setStatus(_ text: String) {
        statusText = text
        qrImage = nil
    }

    private func showQr(_ data: Data) {
        guard let image = UIImage(data: data) else { return }
        qrImage = image
        statusText = nil
        loginVisible = false
    }

    private func pause(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    private func runLoop() async {
        while !Task.isCancelled {
            guard let login = Store.login, let password = Store.password else {
                setStatus("Войдите по логину и паролю")
                loginVisible = true
                dotGreen = false
                await pause(1)
                continue
            }

            if host == nil {
                setStatus("Поиск ПК…")
                host = await PcClient.findHost(cached: Store.host)
                if let found = host {
                    Store.host = found
                }
            }
            guard let currentHost = host else {
                setStatus("Нет связи с ПК")
                dotGreen = false
                await pause(2)
                continue
            }

            if token == nil {
                let result = await PcClient.login(host: currentHost, login: login,
                                                  password: password, device: Store.deviceId())
                switch result {
                case .ok(let issued):
                    token = issued
                case .rejected:
                    setStatus("НЕВЕРНЫЙ ЛОГИН ИЛИ ПАРОЛЬ — введите другие")
                    loginVisible = true
                    dotGreen = false
                    await pause(1.5)
                    continue
                case .deviceBlocked:
                    setStatus("Аккаунт привязан к другому телефону")
                    loginVisible = true
                    dotGreen = false
                    await pause(1.5)
                    continue
                case .network:
                    host = nil
                    setStatus("Нет связи с ПК")
                    dotGreen = false
                    await pause(2)
                    continue
                }
            }

            guard let session = token else { continue }
            switch await PcClient.fetchQr(host: currentHost, token: session) {
            case .ok(let data):
                showQr(data)
                dotGreen = true
            case .inactive:
                setStatus("Перекличка не начата")
                dotGreen = true
            case .stale:
                token = nil
                continue
            case .error:
                host = nil
                setStatus("Нет связи с ПК")
                dotGreen = false
            }
            await pause(5)
        }
    }

    private func toast(_ text: String) {
        toastText = text
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if !Task.isCancelled {
                self?.toastText = nil
            }
        }
    }
}
