import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class GoogleOAuthTests: XCTestCase {
    override func tearDown() {
        GoogleOAuthURLProtocolStub.handler = nil
        super.tearDown()
    }

    func testSystemBrowserSessionOwnsAnEphemeralLoopbackCallback() async throws {
        let browser = SystemBrowserGoogleOAuthSession(timeout: 3) { authorizationURL in
            guard let authorization = URLComponents(url: authorizationURL, resolvingAgainstBaseURL: false),
                  let redirectValue = authorization.queryItems?.first(where: { $0.name == "redirect_uri" })?.value,
                  let redirect = URL(string: redirectValue),
                  let state = authorization.queryItems?.first(where: { $0.name == "state" })?.value,
                  var callback = URLComponents(url: redirect, resolvingAgainstBaseURL: false)
            else { return false }
            callback.queryItems = [
                URLQueryItem(name: "code", value: "loopback-code"),
                URLQueryItem(name: "state", value: state),
            ]
            guard let callbackURL = callback.url else { return false }
            Task { _ = try? await URLSession.shared.data(from: callbackURL) }
            return true
        }
        let request = GoogleOAuthRequest(
            authorizationEndpoint: URL(string: "https://accounts.example/authorize")!,
            clientID: "desktop.apps.googleusercontent.com",
            scopes: ["openid"],
            state: "expected-state",
            nonce: "expected-nonce",
            codeChallenge: "challenge"
        )

        let callback = try await browser.authorize(request)

        XCTAssertEqual(callback.redirectURI.host, "127.0.0.1")
        XCTAssertNotNil(callback.redirectURI.port)
        XCTAssertEqual(callback.redirectURI.path, "/oauth/callback")
        XCTAssertEqual(callback.code, "loopback-code")
        XCTAssertEqual(callback.state, "expected-state")
    }

    func testPKCEStateNonceAndIdentityProofStayBoundAcrossBrowserAndTokenExchange() async throws {
        let browser = RecordingBrowserSession(mode: .success)
        let exchanger = RecordingTokenExchanger(idToken: "signed-google-id-token")
        let flow = GoogleOAuthFlow(
            clientID: "desktop.apps.googleusercontent.com",
            clientSecret: "test-client-secret",
            browserSession: browser,
            tokenExchanger: exchanger,
            authorizationEndpoint: URL(string: "https://accounts.example/authorize")!
        )

        let proof = try await flow.signIn()
        let recordedRequest = await browser.recordedRequest()
        let recordedExchange = await exchanger.recordedExchange()
        let request = try XCTUnwrap(recordedRequest)
        let exchange = try XCTUnwrap(recordedExchange)

        XCTAssertEqual(proof.idToken, "signed-google-id-token")
        XCTAssertEqual(proof.nonce, request.nonce)
        XCTAssertEqual(request.scopes, ["openid", "email", "profile"])
        XCTAssertEqual(request.state.count, 43)
        XCTAssertEqual(request.nonce.count, 43)
        XCTAssertGreaterThanOrEqual(exchange.codeVerifier.count, 64)
        XCTAssertEqual(
            request.codeChallenge,
            Data(SHA256.hash(data: Data(exchange.codeVerifier.utf8))).testBase64URL()
        )
        XCTAssertEqual(exchange.code, "one-time-code")
        XCTAssertEqual(exchange.clientID, request.clientID)
        XCTAssertTrue(exchange.includedClientSecret)
    }

    func testStateMismatchFailsBeforeTokenExchange() async {
        let browser = RecordingBrowserSession(mode: .stateMismatch)
        let exchanger = RecordingTokenExchanger(idToken: "must-not-be-used")
        let flow = GoogleOAuthFlow(
            clientID: "desktop.apps.googleusercontent.com",
            browserSession: browser,
            tokenExchanger: exchanger
        )

        await XCTAssertThrowsErrorAsync(try await flow.signIn()) { error in
            XCTAssertEqual(error as? GoogleOAuthError, .callbackRejected)
        }
        let exchangeCount = await exchanger.exchangeCount()
        XCTAssertEqual(exchangeCount, 0)
    }

    func testProviderCancellationIsNotTreatedAsAProof() async {
        let browser = RecordingBrowserSession(mode: .cancelled)
        let exchanger = RecordingTokenExchanger(idToken: "must-not-be-used")
        let flow = GoogleOAuthFlow(
            clientID: "desktop.apps.googleusercontent.com",
            browserSession: browser,
            tokenExchanger: exchanger
        )

        await XCTAssertThrowsErrorAsync(try await flow.signIn()) { error in
            XCTAssertEqual(error as? GoogleOAuthError, .cancelled)
        }
        let exchangeCount = await exchanger.exchangeCount()
        XCTAssertEqual(exchangeCount, 0)
    }

    func testTokenEndpointRejectionKeepsOnlySafeProviderCode() async throws {
        GoogleOAuthURLProtocolStub.handler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertFalse(String(decoding: request.capturedBody() ?? Data(), as: UTF8.self).contains("client_secret"))
            let response = HTTPURLResponse(
                url: try XCTUnwrap(request.url),
                statusCode: 400,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (
                response,
                Data(#"{"error":"invalid_grant","error_description":"authorization code details"}"#.utf8)
            )
        }
        let exchanger = GoogleOAuthTokenExchanger(session: tokenStubSession())

        await XCTAssertThrowsErrorAsync(
            try await exchanger.exchange(
                code: "one-time-code",
                codeVerifier: "verifier",
                redirectURI: URL(string: "http://127.0.0.1:54321/oauth/callback")!,
                clientID: "desktop.apps.googleusercontent.com",
                clientSecret: nil
            )
        ) { error in
            XCTAssertEqual(
                error as? GoogleOAuthError,
                .tokenEndpointRejected(statusCode: 400, providerCode: "invalid_grant")
            )
            XCTAssertFalse(String(describing: error).contains("authorization code details"))
        }
    }

    func testTokenExchangeIncludesOptionalClientSecretInFormBody() async throws {
        GoogleOAuthURLProtocolStub.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            let body = String(decoding: try XCTUnwrap(request.capturedBody()), as: UTF8.self)
            let query = try XCTUnwrap(URLComponents(string: "https://token.example/?\(body)")?.queryItems)
            let fields = Dictionary(uniqueKeysWithValues: query.map { ($0.name, $0.value ?? "") })
            XCTAssertEqual(fields["client_id"], "desktop.apps.googleusercontent.com")
            XCTAssertEqual(fields["client_secret"], "test-secret+value")
            XCTAssertEqual(fields["code"], "one-time-code")
            XCTAssertEqual(fields["code_verifier"], "verifier")

            let response = HTTPURLResponse(
                url: try XCTUnwrap(request.url),
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data(#"{"id_token":"signed-id-token"}"#.utf8))
        }
        let exchanger = GoogleOAuthTokenExchanger(session: tokenStubSession())

        let token = try await exchanger.exchange(
            code: "one-time-code",
            codeVerifier: "verifier",
            redirectURI: URL(string: "http://127.0.0.1:54321/oauth/callback")!,
            clientID: "desktop.apps.googleusercontent.com",
            clientSecret: "test-secret+value"
        )

        XCTAssertEqual(token, "signed-id-token")
    }

    func testSuccessfulTokenResponseStillRequiresIDToken() async throws {
        GoogleOAuthURLProtocolStub.handler = { request in
            let response = HTTPURLResponse(
                url: try XCTUnwrap(request.url),
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data(#"{"access_token":"must-not-be-used"}"#.utf8))
        }
        let exchanger = GoogleOAuthTokenExchanger(session: tokenStubSession())

        await XCTAssertThrowsErrorAsync(
            try await exchanger.exchange(
                code: "one-time-code",
                codeVerifier: "verifier",
                redirectURI: URL(string: "http://127.0.0.1:54321/oauth/callback")!,
                clientID: "desktop.apps.googleusercontent.com",
                clientSecret: nil
            )
        ) { error in
            XCTAssertEqual(error as? GoogleOAuthError, .invalidTokenResponse)
        }
    }
}

private func tokenStubSession() -> URLSession {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [GoogleOAuthURLProtocolStub.self]
    return URLSession(configuration: configuration)
}

private final class GoogleOAuthURLProtocolStub: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

private extension URLRequest {
    func capturedBody() -> Data? {
        if let httpBody { return httpBody }
        guard let stream = httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private actor RecordingBrowserSession: GoogleOAuthBrowserSession {
    enum Mode { case success, stateMismatch, cancelled }

    private let mode: Mode
    private var request: GoogleOAuthRequest?

    init(mode: Mode) { self.mode = mode }

    func authorize(_ request: GoogleOAuthRequest) async throws -> GoogleOAuthCallback {
        self.request = request
        return GoogleOAuthCallback(
            redirectURI: URL(string: "http://127.0.0.1:54321/oauth/callback")!,
            code: mode == .cancelled ? nil : "one-time-code",
            state: mode == .stateMismatch ? "attacker-state" : request.state,
            error: mode == .cancelled ? "access_denied" : nil
        )
    }

    func recordedRequest() -> GoogleOAuthRequest? { request }
}

private actor RecordingTokenExchanger: GoogleOAuthTokenExchanging {
    struct Exchange {
        let code: String
        let codeVerifier: String
        let redirectURI: URL
        let clientID: String
        let includedClientSecret: Bool
    }

    private let idToken: String
    private var exchange: Exchange?
    private var count = 0

    init(idToken: String) { self.idToken = idToken }

    func exchange(
        code: String,
        codeVerifier: String,
        redirectURI: URL,
        clientID: String,
        clientSecret: String?
    ) async throws -> String {
        count += 1
        exchange = Exchange(
            code: code,
            codeVerifier: codeVerifier,
            redirectURI: redirectURI,
            clientID: clientID,
            includedClientSecret: clientSecret != nil
        )
        return idToken
    }

    func recordedExchange() -> Exchange? { exchange }
    func exchangeCount() -> Int { count }
}

private extension Data {
    func testBase64URL() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ handler: (Error) -> Void = { _ in }
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw")
    } catch {
        handler(error)
    }
}
