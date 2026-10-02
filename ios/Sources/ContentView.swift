import SwiftUI

struct ContentView: View {
    @StateObject private var model = StudentModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var hasBeenBackground = false
    @State private var started = false

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()

            VStack {
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 110, height: 110)
                    .padding(.top, 40)
                Spacer()
            }

            if let image = model.qrImage {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .frame(width: 300, height: 300)
                    .onLongPressGesture(minimumDuration: 0.8) {
                        model.longPressQr()
                    }
            }

            if let text = model.statusText {
                Text(text)
                    .font(.system(size: 15))
                    .foregroundColor(Theme.textDim)
                    .multilineTextAlignment(.center)
                    .padding(32)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        model.tapStatus()
                    }
                    .onLongPressGesture(minimumDuration: 0.8) {
                        model.longPressStatus()
                    }
            }

            if model.loginVisible {
                LoginCard { login, password in
                    model.submitLogin(login: login, password: password)
                }
            }

            if model.dotVisible {
                VStack {
                    HStack {
                        Spacer()
                        Circle()
                            .fill(model.dotGreen ? Theme.green : Theme.red)
                            .frame(width: 16, height: 16)
                            .padding(.top, 22)
                            .padding(.trailing, 22)
                    }
                    Spacer()
                }
            }

            if let toast = model.toastText {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.system(size: 14))
                        .foregroundColor(Theme.text)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(Theme.panel2)
                        .cornerRadius(12)
                        .padding(.bottom, 60)
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            if !started {
                started = true
                model.start(auto: true)
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                hasBeenBackground = true
                model.stop()
            } else if phase == .active && hasBeenBackground {
                hasBeenBackground = false
                model.start(auto: true)
            }
        }
    }
}

struct LoginCard: View {
    var submit: (String, String) -> Void

    @State private var login = ""
    @State private var password = ""

    var body: some View {
        VStack(spacing: 0) {
            TextField("", text: $login, prompt: Text("Логин").foregroundColor(Theme.textDim))
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .font(.system(size: 15))
                .foregroundColor(Theme.text)
                .padding(14)
                .frame(width: 260)
                .background(Theme.panel2)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.border, lineWidth: 1))
                .cornerRadius(12)

            SecureField("", text: $password, prompt: Text("Пароль").foregroundColor(Theme.textDim))
                .font(.system(size: 15))
                .foregroundColor(Theme.text)
                .padding(14)
                .frame(width: 260)
                .background(Theme.panel2)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.border, lineWidth: 1))
                .cornerRadius(12)
                .padding(.top, 10)

            Button {
                submit(login, password)
            } label: {
                Text("Войти")
                    .font(.system(size: 15))
                    .foregroundColor(.white)
                    .frame(width: 260, height: 48)
            }
            .buttonStyle(AccentButtonStyle())
            .padding(.top, 16)
        }
        .padding(.horizontal, 24)
        .padding(.top, 26)
        .padding(.bottom, 24)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.border, lineWidth: 1))
        .cornerRadius(20)
    }
}

struct AccentButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Theme.accentPressed : Theme.accent)
            .cornerRadius(12)
    }
}
