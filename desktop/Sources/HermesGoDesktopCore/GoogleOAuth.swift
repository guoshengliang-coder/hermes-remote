import AppKit
import CryptoKit
import Foundation
import Network
import Security

public struct GoogleOAuthRequest: Equatable, Sendable {
    public let authorizationEndpoint: URL
    public let clientID: String
    public let scopes: [String]
    public let state: String
    public let nonce: String
    public let codeChallenge: String

    public init(
        authorizationEndpoint: URL,
        clientID: String,
        scopes: [String],
        state: String,
        nonce: String,
        codeChallenge: String
    ) {
        self.authorizationEndpoint = authorizationEndpoint
        self.clientID = clientID
        self.scopes = scopes
        self.state = state
        self.nonce = nonce
        self.codeChallenge = codeChallenge
    }
}

public struct GoogleOAuthCallback: Equatable, Sendable {
    public let redirectURI: URL
    public let code: String?
    public let state: String?
    public let error: String?

    public init(redirectURI: URL, code: String?, state: String?, error: String?) {
        self.redirectURI = redirectURI
        self.code = code
        self.state = state
        self.error = error
    }
}

public protocol GoogleOAuthBrowserSession: Sendable {
    func authorize(_ request: GoogleOAuthRequest) async throws -> GoogleOAuthCallback
}

public protocol GoogleOAuthTokenExchanging: Sendable {
    func exchange(
        code: String,
        codeVerifier: String,
        redirectURI: URL,
        clientID: String
    ) async throws -> String
}

public protocol GoogleOAuthPerforming: Sendable {
    func signIn() async throws -> GoogleIdentityProof
}

public actor GoogleOAuthFlow: GoogleOAuthPerforming {
    private let clientID: String
    private let browserSession: any GoogleOAuthBrowserSession
    private let tokenExchanger: any GoogleOAuthTokenExchanging
    private let authorizationEndpoint: URL

    public init(
        clientID: String,
        browserSession: any GoogleOAuthBrowserSession = SystemBrowserGoogleOAuthSession(),
        tokenExchanger: any GoogleOAuthTokenExchanging = GoogleOAuthTokenExchanger(),
        authorizationEndpoint: URL = URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!
    ) {
        self.clientID = clientID.trimmingCharacters(in: .whitespacesAndNewlines)
        self.browserSession = browserSession
        self.tokenExchanger = tokenExchanger
        self.authorizationEndpoint = authorizationEndpoint
    }

    public func signIn() async throws -> GoogleIdentityProof {
        guard !clientID.isEmpty else { throw GoogleOAuthError.configurationMissing }
        let state = try secureRandomURLString(byteCount: 32)
        let nonce = try secureRandomURLString(byteCount: 32)
        let verifier = try secureRandomURLString(byteCount: 48)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncodedString()
        let request = GoogleOAuthRequest(
            authorizationEndpoint: authorizationEndpoint,
            clientID: clientID,
            scopes: ["openid", "email", "profile"],
            state: state,
            nonce: nonce,
            codeChallenge: challenge
        )
        let callback = try await browserSession.authorize(request)

        guard callback.error == nil else {
            if callback.error == "access_denied" { throw GoogleOAuthError.cancelled }
            throw GoogleOAuthError.callbackRejected
        }
        guard let callbackState = callback.state,
              securelyEqual(callbackState, state),
              let code = callback.code,
              !code.isEmpty,
              code.count <= 4_096
        else { throw GoogleOAuthError.callbackRejected }

        let idToken = try await tokenExchanger.exchange(
            code: code,
            codeVerifier: verifier,
            redirectURI: callback.redirectURI,
            clientID: clientID
        )
        guard !idToken.isEmpty, idToken.count <= 16_384 else {
            throw GoogleOAuthError.invalidTokenResponse
        }
        return GoogleIdentityProof(idToken: idToken, nonce: nonce)
    }
}

public final class SystemBrowserGoogleOAuthSession: GoogleOAuthBrowserSession, @unchecked Sendable {
    private let timeout: TimeInterval
    private let queue: DispatchQueue
    private let openAuthorizationURL: @MainActor @Sendable (URL) -> Bool

    public init(
        timeout: TimeInterval = 180,
        openAuthorizationURL: @escaping @MainActor @Sendable (URL) -> Bool = {
            NSWorkspace.shared.open($0)
        }
    ) {
        self.timeout = timeout
        self.openAuthorizationURL = openAuthorizationURL
        queue = DispatchQueue(label: "com.hermesgo.desktop.oauth-loopback")
    }

    public func authorize(_ request: GoogleOAuthRequest) async throws -> GoogleOAuthCallback {
        let cancellation = OAuthCancellationRelay()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let completion = OAuthCallbackCompletion(continuation: continuation)
                cancellation.install {
                    completion.finish(.failure(GoogleOAuthError.cancelled))
                }
                do {
                    let parameters = NWParameters.tcp
                    guard let address = IPv4Address("127.0.0.1") else {
                        throw GoogleOAuthError.callbackListenerFailed
                    }
                    parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(address), port: .any)
                    let listener = try NWListener(using: parameters)
                    completion.attach(listener)

                    listener.stateUpdateHandler = { [weak completion] state in
                        guard let completion else { return }
                        switch state {
                        case .ready:
                            guard let port = listener.port,
                                  let redirectURI = URL(string: "http://127.0.0.1:\(port.rawValue)/oauth/callback"),
                                  let authorizationURL = makeAuthorizationURL(request, redirectURI: redirectURI)
                            else {
                                completion.finish(.failure(GoogleOAuthError.callbackListenerFailed))
                                return
                            }
                            completion.setRedirectURI(redirectURI)
                            Task { @MainActor in
                                guard completion.isPending else { return }
                                guard self.openAuthorizationURL(authorizationURL) else {
                                    completion.finish(.failure(GoogleOAuthError.browserUnavailable))
                                    return
                                }
                            }
                        case .failed:
                            completion.finish(.failure(GoogleOAuthError.callbackListenerFailed))
                        default:
                            break
                        }
                    }

                    listener.newConnectionHandler = { [weak completion] connection in
                        guard let completion else {
                            connection.cancel()
                            return
                        }
                        connection.start(queue: self.queue)
                        receiveHTTPRequest(connection: connection, completion: completion)
                    }
                    listener.start(queue: queue)
                    queue.asyncAfter(deadline: .now() + timeout) {
                        completion.finish(.failure(GoogleOAuthError.callbackTimedOut))
                    }
                } catch let error as GoogleOAuthError {
                    completion.finish(.failure(error))
                } catch {
                    completion.finish(.failure(GoogleOAuthError.callbackListenerFailed))
                }
            }
        } onCancel: {
            cancellation.cancel()
        }
    }
}

public actor GoogleOAuthTokenExchanger: GoogleOAuthTokenExchanging {
    private let session: URLSession
    private let tokenEndpoint: URL

    public init(
        session: URLSession = .shared,
        tokenEndpoint: URL = URL(string: "https://oauth2.googleapis.com/token")!
    ) {
        self.session = session
        self.tokenEndpoint = tokenEndpoint
    }

    public func exchange(
        code: String,
        codeVerifier: String,
        redirectURI: URL,
        clientID: String
    ) async throws -> String {
        var request = URLRequest(url: tokenEndpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = formEncoded([
            ("client_id", clientID),
            ("code", code),
            ("code_verifier", codeVerifier),
            ("grant_type", "authorization_code"),
            ("redirect_uri", redirectURI.absoluteString),
        ])

        let result: (Data, URLResponse)
        do {
            result = try await session.data(for: request)
        } catch {
            throw GoogleOAuthError.tokenExchangeFailed
        }
        guard result.0.count <= 64 * 1024,
              let response = result.1 as? HTTPURLResponse,
              (200..<300).contains(response.statusCode),
              let token = try? JSONDecoder().decode(GoogleTokenResponse.self, from: result.0),
              let idToken = token.idToken
        else { throw GoogleOAuthError.invalidTokenResponse }
        return idToken
    }
}

private final class OAuthCallbackCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<GoogleOAuthCallback, Error>?
    private var listener: NWListener?
    private var redirectURI: URL?

    init(continuation: CheckedContinuation<GoogleOAuthCallback, Error>) {
        self.continuation = continuation
    }

    func attach(_ listener: NWListener) {
        lock.lock()
        self.listener = listener
        lock.unlock()
    }

    func setRedirectURI(_ value: URL) {
        lock.lock()
        redirectURI = value
        lock.unlock()
    }

    func currentRedirectURI() -> URL? {
        lock.lock()
        defer { lock.unlock() }
        return redirectURI
    }

    var isPending: Bool {
        lock.lock()
        defer { lock.unlock() }
        return continuation != nil
    }

    func finish(_ result: Result<GoogleOAuthCallback, Error>) {
        lock.lock()
        guard let continuation else {
            lock.unlock()
            return
        }
        self.continuation = nil
        let listener = self.listener
        self.listener = nil
        lock.unlock()
        listener?.cancel()
        continuation.resume(with: result)
    }
}

private final class OAuthCancellationRelay: @unchecked Sendable {
    private let lock = NSLock()
    private var action: (() -> Void)?
    private var cancelled = false

    func install(_ action: @escaping () -> Void) {
        lock.lock()
        if cancelled {
            lock.unlock()
            action()
            return
        }
        self.action = action
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let action = self.action
        self.action = nil
        lock.unlock()
        action?()
    }
}

private func receiveHTTPRequest(
    connection: NWConnection,
    completion: OAuthCallbackCompletion,
    accumulated: Data = Data()
) {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { data, _, isComplete, error in
        var buffer = accumulated
        if let data { buffer.append(data) }
        guard buffer.count <= 8_192 else {
            sendBrowserResponse(connection, status: "400 Bad Request", message: "Invalid sign-in callback.")
            completion.finish(.failure(GoogleOAuthError.callbackRejected))
            return
        }
        if buffer.range(of: Data("\r\n\r\n".utf8)) == nil, error == nil, !isComplete {
            receiveHTTPRequest(connection: connection, completion: completion, accumulated: buffer)
            return
        }
        guard error == nil,
              let requestText = String(data: buffer, encoding: .utf8),
              let requestLine = requestText.split(separator: "\r\n", maxSplits: 1).first,
              requestLine.hasPrefix("GET "),
              let target = requestLine.split(separator: " ").dropFirst().first,
              let redirectURI = completion.currentRedirectURI(),
              let callbackURL = URL(string: String(target), relativeTo: redirectURI)?.absoluteURL,
              callbackURL.scheme == redirectURI.scheme,
              callbackURL.host == redirectURI.host,
              callbackURL.port == redirectURI.port,
              callbackURL.path == "/oauth/callback",
              let components = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)
        else {
            sendBrowserResponse(connection, status: "400 Bad Request", message: "Invalid sign-in callback.")
            completion.finish(.failure(GoogleOAuthError.callbackRejected))
            return
        }
        guard let values = uniqueQueryValues(components.queryItems ?? []) else {
            sendBrowserResponse(connection, status: "400 Bad Request", message: "Invalid sign-in callback.")
            completion.finish(.failure(GoogleOAuthError.callbackRejected))
            return
        }
        let callback = GoogleOAuthCallback(
            redirectURI: redirectURI,
            code: values["code"] ?? nil,
            state: values["state"] ?? nil,
            error: values["error"] ?? nil
        )
        sendBrowserResponse(
            connection,
            status: "200 OK",
            message: "Sign-in received. You can close this window and return to Hermes Go Desktop."
        )
        completion.finish(.success(callback))
    }
}

private func uniqueQueryValues(_ items: [URLQueryItem]) -> [String: String?]? {
    var result: [String: String?] = [:]
    for item in items {
        guard result[item.name] == nil else { return nil }
        result[item.name] = item.value
    }
    return result
}

private func sendBrowserResponse(_ connection: NWConnection, status: String, message: String) {
    let body = """
    <!doctype html><meta charset="utf-8"><title>Hermes GO</title>
    <style>body{font:16px -apple-system;padding:48px;max-width:560px;margin:auto}</style>
    <h1>Hermes GO</h1><p>\(message)</p>
    """
    let response = "HTTP/1.1 \(status)\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nX-Content-Type-Options: nosniff\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
    connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
}

private func makeAuthorizationURL(_ request: GoogleOAuthRequest, redirectURI: URL) -> URL? {
    guard var components = URLComponents(url: request.authorizationEndpoint, resolvingAgainstBaseURL: false) else {
        return nil
    }
    components.queryItems = [
        URLQueryItem(name: "client_id", value: request.clientID),
        URLQueryItem(name: "redirect_uri", value: redirectURI.absoluteString),
        URLQueryItem(name: "response_type", value: "code"),
        URLQueryItem(name: "scope", value: request.scopes.joined(separator: " ")),
        URLQueryItem(name: "code_challenge", value: request.codeChallenge),
        URLQueryItem(name: "code_challenge_method", value: "S256"),
        URLQueryItem(name: "state", value: request.state),
        URLQueryItem(name: "nonce", value: request.nonce),
        URLQueryItem(name: "prompt", value: "select_account"),
    ]
    return components.url
}

private func formEncoded(_ values: [(String, String)]) -> Data? {
    var components = URLComponents()
    components.queryItems = values.map { URLQueryItem(name: $0.0, value: $0.1) }
    return components.percentEncodedQuery?.data(using: .utf8)
}

private struct GoogleTokenResponse: Decodable {
    let idToken: String?

    enum CodingKeys: String, CodingKey {
        case idToken = "id_token"
    }
}

private func secureRandomURLString(byteCount: Int) throws -> String {
    var bytes = [UInt8](repeating: 0, count: byteCount)
    let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    guard status == errSecSuccess else { throw AccountSecretStoreError.randomGeneration(status) }
    return Data(bytes).base64URLEncodedString()
}

private func securelyEqual(_ lhs: String, _ rhs: String) -> Bool {
    let left = Array(lhs.utf8)
    let right = Array(rhs.utf8)
    guard left.count == right.count else { return false }
    return zip(left, right).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
