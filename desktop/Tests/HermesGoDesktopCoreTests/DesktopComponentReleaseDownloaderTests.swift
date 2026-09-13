import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentReleaseDownloaderTests: XCTestCase {
    override func tearDown() {
        ComponentURLProtocol.handler = nil
        super.tearDown()
    }

    func testFreshDownloadVerifiesFullDigestBeforeExposingFinalName() async throws {
        let payload = Data("complete component".utf8)
        ComponentURLProtocol.handler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Range"))
            return (200, ["Content-Length": "\(payload.count)"], payload)
        }
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let artifact = metadata(payload)

        let result = try await downloader().download(artifact, into: root)

        XCTAssertEqual(result.lastPathComponent, artifact.fileName)
        XCTAssertEqual(try Data(contentsOf: result), payload)
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: root.appendingPathComponent(".\(artifact.fileName).partial").path
        ))
    }

    func testExistingPartialUsesExactRangeAndThenVerifiesWholeFile() async throws {
        let payload = Data("resume this component".utf8)
        let split = 7
        let artifact = metadata(payload)
        let root = temporaryRoot()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let partial = root.appendingPathComponent(".\(artifact.fileName).partial")
        try payload.prefix(split).write(to: partial)
        ComponentURLProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Range"), "bytes=\(split)-")
            return (
                206,
                [
                    "Content-Length": "\(payload.count - split)",
                    "Content-Range": "bytes \(split)-\(payload.count - 1)/\(payload.count)",
                ],
                Data(payload.dropFirst(split))
            )
        }
        defer { try? FileManager.default.removeItem(at: root) }

        let result = try await downloader().download(artifact, into: root)

        XCTAssertEqual(try Data(contentsOf: result), payload)
    }

    func testResumeRejectsServerThatIgnoresRangeAndKeepsKnownPartial() async throws {
        let payload = Data("resume this component".utf8)
        let artifact = metadata(payload)
        let root = temporaryRoot()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let partial = root.appendingPathComponent(".\(artifact.fileName).partial")
        try payload.prefix(7).write(to: partial)
        ComponentURLProtocol.handler = { _ in
            (200, ["Content-Length": "\(payload.count)"], payload)
        }
        defer { try? FileManager.default.removeItem(at: root) }

        do {
            _ = try await downloader().download(artifact, into: root)
            XCTFail("Expected an exact Range response")
        } catch {
            XCTAssertEqual(error as? DesktopComponentDownloadError, .invalidResponse)
        }
        XCTAssertEqual(try Data(contentsOf: partial), payload.prefix(7))
    }

    func testFullSizeDigestMismatchDeletesPoisonedPartial() async throws {
        let payload = Data("complete component".utf8)
        let artifact = metadata(payload, sha256: String(repeating: "0", count: 64))
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        ComponentURLProtocol.handler = { _ in
            (200, ["Content-Length": "\(payload.count)"], payload)
        }

        do {
            _ = try await downloader().download(artifact, into: root)
            XCTFail("Expected digest mismatch")
        } catch {
            XCTAssertEqual(error as? DesktopComponentDownloadError, .digestMismatch)
        }
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: root.appendingPathComponent(".\(artifact.fileName).partial").path
        ))
    }

    private func downloader() -> DesktopComponentReleaseDownloader {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ComponentURLProtocol.self]
        return DesktopComponentReleaseDownloader(configuration: configuration)
    }

    private func metadata(
        _ payload: Data,
        sha256: String? = nil
    ) -> DesktopComponentReleaseArtifactV2 {
        let name = "Hermes-Component-connector-0.3.0-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: .connector,
            version: "0.3.0",
            architecture: "arm64",
            installPhase: .bootstrap,
            requiredForBootstrap: true,
            reuseContract: .exactContent,
            fileName: name,
            entrypoint: "bin/hermes-connector",
            downloadURL: "https://downloads.example/desktop/components/\(name)",
            sizeBytes: Int64(payload.count),
            sha256: sha256 ?? SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined(),
            contentSHA256: String(repeating: "a", count: 64),
            dependencies: []
        )
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-component-download-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
    }
}

private final class ComponentURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, [String: String], Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        let result = handler(request)
        let response = HTTPURLResponse(
            url: request.url!, statusCode: result.0, httpVersion: "HTTP/1.1", headerFields: result.1
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: result.2)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
