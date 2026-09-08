import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopReleaseDownloaderTests: XCTestCase {
    override func tearDown() {
        ReleaseURLProtocol.handler = nil
        super.tearDown()
    }

    func testManifestAndArtifactRequireExactSuccessfulResponse() async throws {
        let payload = Data("signed manifest".utf8)
        ReleaseURLProtocol.handler = { request in
            (200, ["Content-Length": "\(payload.count)"], payload)
        }
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let downloader = DesktopReleaseDownloader(session: session())
        let manifestURL = URL(string: "https://downloads.example/desktop/manifest.json")!

        let fetchedManifest = try await downloader.fetchManifest(from: manifestURL)
        XCTAssertEqual(fetchedManifest, payload)
        let artifact = metadata(url: "https://downloads.example/desktop/Hermes-Connector-0.2.0-arm64.tar.gz", size: payload.count)
        let file = try await downloader.download(artifact, into: root)
        XCTAssertEqual(try Data(contentsOf: file), payload)
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
    }

    func testArtifactContentLengthOrFinalSizeMismatchLeavesNoDestination() async throws {
        let payload = Data("short".utf8)
        ReleaseURLProtocol.handler = { _ in
            (200, ["Content-Length": "\(payload.count)"], payload)
        }
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let artifact = metadata(url: "https://downloads.example/Hermes-Connector-0.2.0-arm64.tar.gz", size: payload.count + 1)

        do {
            _ = try await DesktopReleaseDownloader(session: session()).download(artifact, into: root)
            XCTFail("Expected size mismatch")
        } catch {
            XCTAssertEqual(error as? DesktopReleaseDownloadError, .invalidResponse)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(artifact.fileName).path))
    }

    func testRedirectIsRejectedInsteadOfFollowingAnotherOrigin() async throws {
        ReleaseURLProtocol.handler = { _ in
            (302, ["Location": "https://evil.example/manifest.json"], Data())
        }
        do {
            _ = try await DesktopReleaseDownloader(session: session()).fetchManifest(
                from: URL(string: "https://downloads.example/manifest.json")!
            )
            XCTFail("Expected redirect rejection")
        } catch {
            XCTAssertEqual(error as? DesktopReleaseDownloadError, .invalidResponse)
        }
    }

    func testOversizedManifestIsRejectedWithoutLoadingItAsApplicationData() async throws {
        let oversized = Data(repeating: 0x61, count: 256 * 1024 + 1)
        ReleaseURLProtocol.handler = { _ in
            (200, ["Content-Length": "\(oversized.count)"], oversized)
        }
        do {
            _ = try await DesktopReleaseDownloader(session: session()).fetchManifest(
                from: URL(string: "https://downloads.example/manifest.json")!
            )
            XCTFail("Expected manifest size rejection")
        } catch {
            XCTAssertEqual(error as? DesktopReleaseDownloadError, .responseTooLarge)
        }
    }

    private func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ReleaseURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func metadata(url: String, size: Int) -> DesktopReleaseArtifact {
        DesktopReleaseArtifact(
            component: .connector,
            version: "0.2.0",
            fileName: URL(string: url)!.lastPathComponent,
            entrypoint: "bin/hermes-connector",
            downloadURL: url,
            sizeBytes: Int64(size),
            sha256: String(repeating: "a", count: 64)
        )
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-download-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
    }
}

private final class ReleaseURLProtocol: URLProtocol, @unchecked Sendable {
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
            url: request.url!,
            statusCode: result.0,
            httpVersion: "HTTP/1.1",
            headerFields: result.1
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: result.2)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
