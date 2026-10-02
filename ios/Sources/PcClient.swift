import Foundation
import Darwin

enum LoginResult {
    case ok(String)
    case rejected
    case deviceBlocked
    case network
}

enum QrResult {
    case ok(Data)
    case inactive
    case stale
    case error
}

enum PcClient {
    static let port = 8090

    private static func makeSession(timeout: TimeInterval) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout + 1
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }

    private static let normalSession = makeSession(timeout: 2.5)
    private static let probeSession = makeSession(timeout: 0.35)

    static func ping(_ host: String) async -> Bool {
        guard let url = URL(string: "http://\(host):\(port)/api/ping") else { return false }
        do {
            let (data, response) = try await probeSession.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return false }
            return String(data: data, encoding: .utf8)?.contains("visits11-server") == true
        } catch {
            return false
        }
    }

    static func login(host: String, login: String, password: String, device: String) async -> LoginResult {
        guard let url = URL(string: "http://\(host):\(port)/api/login"),
              let body = try? JSONSerialization.data(withJSONObject: [
                "login": login,
                "password": password,
                "device": device
              ]) else { return .network }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        do {
            let (data, response) = try await normalSession.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return .network }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return .rejected
            }
            if json["device"] as? Bool == true { return .deviceBlocked }
            guard json["ok"] as? Bool == true,
                  let token = json["token"] as? String,
                  !token.isEmpty else { return .rejected }
            return .ok(token)
        } catch {
            return .network
        }
    }

    static func fetchQr(host: String, token: String) async -> QrResult {
        var components = URLComponents()
        components.scheme = "http"
        components.host = host
        components.port = port
        components.path = "/api/qr"
        components.queryItems = [URLQueryItem(name: "token", value: token)]
        guard let url = components.url else { return .error }

        do {
            let (data, response) = try await normalSession.data(from: url)
            switch (response as? HTTPURLResponse)?.statusCode {
            case 200:
                return data.isEmpty ? .error : .ok(data)
            case 204:
                return .inactive
            case 401:
                return .stale
            default:
                return .error
            }
        } catch {
            return .error
        }
    }

    static func findHost(cached: String?) async -> String? {
        if let cached, await ping(cached) {
            return cached
        }
        let targets = subnetTargets()
        if targets.isEmpty { return nil }

        return await withTaskGroup(of: String?.self) { group in
            var iterator = targets.makeIterator()
            var found: String?
            for _ in 0..<32 {
                guard let target = iterator.next() else { break }
                group.addTask { await ping(target) ? target : nil }
            }
            while let result = await group.next() {
                if let result {
                    found = result
                    group.cancelAll()
                    break
                }
                if let target = iterator.next() {
                    group.addTask { await ping(target) ? target : nil }
                }
            }
            return found
        }
    }

    private static func subnetTargets() -> [String] {
        var own: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }

        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = current {
            current = entry.pointee.ifa_next
            guard let address = entry.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  String(cString: entry.pointee.ifa_name) == "en0" else { continue }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &buffer,
                           socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                own.append(String(cString: buffer))
            }
        }

        var targets: [String] = []
        for ip in own {
            guard let lastDot = ip.lastIndex(of: ".") else { continue }
            let base = String(ip[...lastDot])
            for index in 1...254 {
                let candidate = base + String(index)
                if !own.contains(candidate) { targets.append(candidate) }
            }
        }
        return targets
    }
}
