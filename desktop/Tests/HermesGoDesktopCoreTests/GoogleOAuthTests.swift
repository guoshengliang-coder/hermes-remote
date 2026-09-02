import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class GoogleOAuthTests: XCTestCase {
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
    }

    private let idToken: String
    private var exchange: Exchange?
    private var count = 0

    init(idToken: String) { self.idToken = idToken }

    func exchange(
        code: String,
        codeVerifier: String,
        redirectURI: URL,
        clientID: String
    ) async throws -> String {
        count += 1
        exchange = Exchange(
            code: code,
            codeVerifier: codeVerifier,
            redirectURI: redirectURI,
            clientID: clientID
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
