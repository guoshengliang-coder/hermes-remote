import Darwin
import Foundation

public enum DesktopReleaseDownloadError: Error, Equatable, Sendable {
    case invalidRequest
    case invalidResponse
    case responseTooLarge
    case unsafeDestination
    case transportFailed
}

public final class DesktopReleaseDownloader: @unchecked Sendable {
    private static let maximumManifestBytes = 256 * 1024
    private let session: URLSession
    private let fileManager: FileManager

    public init(session: URLSession? = nil, fileManager: FileManager = .default) {
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            configuration.urlCache = nil
            configuration.timeoutIntervalForRequest = 60
            configuration.timeoutIntervalForResource = 15 * 60
            configuration.waitsForConnectivity = false
            self.session = URLSession(configuration: configuration)
        }
        self.fileManager = fileManager
    }

    public func fetchManifest(from url: URL) async throws -> Data {
        guard Self.validHTTPSURL(url) else { throw DesktopReleaseDownloadError.invalidRequest }
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData)
        let delegate = BoundedNoRedirectDownloadDelegate(maximumBytes: Int64(Self.maximumManifestBytes))
        let temporary: URL
        let response: URLResponse
        do {
            (temporary, response) = try await session.download(for: request, delegate: delegate)
        } catch {
            if delegate.didExceedLimit { throw DesktopReleaseDownloadError.responseTooLarge }
            throw DesktopReleaseDownloadError.transportFailed
        }
        defer { try? fileManager.removeItem(at: temporary) }
        try validate(response: response, requestedURL: url, expectedSize: nil)
        guard let attributes = try? fileManager.attributesOfItem(atPath: temporary.path),
              let size = attributes[.size] as? NSNumber,
              size.int64Value <= Self.maximumManifestBytes,
              let data = try? Data(contentsOf: temporary)
        else {
            throw DesktopReleaseDownloadError.responseTooLarge
        }
        return data
    }

    public func download(
        _ artifact: DesktopReleaseArtifact,
        into downloadRoot: URL
    ) async throws -> URL {
        guard let url = URL(string: artifact.downloadURL),
              Self.validHTTPSURL(url),
              artifact.sizeBytes > 0,
              artifact.sizeBytes <= 2 * 1024 * 1024 * 1024,
              artifact.fileName == url.lastPathComponent
        else { throw DesktopReleaseDownloadError.invalidRequest }
        let root = try preparePrivateRoot(downloadRoot)
        let destination = root.appendingPathComponent(artifact.fileName)
        guard !fileManager.fileExists(atPath: destination.path) else {
            throw DesktopReleaseDownloadError.unsafeDestination
        }

        let delegate = BoundedNoRedirectDownloadDelegate(maximumBytes: artifact.sizeBytes)
        let temporary: URL
        let response: URLResponse
        do {
            (temporary, response) = try await session.download(
                for: URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData),
                delegate: delegate
            )
        } catch {
            if delegate.didExceedLimit { throw DesktopReleaseDownloadError.responseTooLarge }
            throw DesktopReleaseDownloadError.transportFailed
        }
        defer { try? fileManager.removeItem(at: temporary) }
        try validate(response: response, requestedURL: url, expectedSize: artifact.sizeBytes)
        guard let attributes = try? fileManager.attributesOfItem(atPath: temporary.path),
              let size = attributes[.size] as? NSNumber,
              size.int64Value == artifact.sizeBytes
        else { throw DesktopReleaseDownloadError.invalidResponse }
        do {
            try fileManager.moveItem(at: temporary, to: destination)
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
        } catch {
            try? fileManager.removeItem(at: destination)
            throw DesktopReleaseDownloadError.unsafeDestination
        }
        return destination
    }

    private func validate(
        response: URLResponse,
        requestedURL: URL,
        expectedSize: Int64?
    ) throws {
        guard let http = response as? HTTPURLResponse,
              http.statusCode == 200,
              http.url == requestedURL
        else { throw DesktopReleaseDownloadError.invalidResponse }
        if let contentLength = http.value(forHTTPHeaderField: "Content-Length"),
           let length = Int64(contentLength),
           let expectedSize,
           length != expectedSize {
            throw DesktopReleaseDownloadError.invalidResponse
        }
    }

    private func preparePrivateRoot(_ value: URL) throws -> URL {
        let root = value.standardizedFileURL.resolvingSymlinksInPath()
        guard root.isFileURL, root.path.hasPrefix("/"), root.path != "/" else {
            throw DesktopReleaseDownloadError.unsafeDestination
        }
        if !fileManager.fileExists(atPath: root.path) {
            do {
                try fileManager.createDirectory(
                    at: root,
                    withIntermediateDirectories: true,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch { throw DesktopReleaseDownloadError.unsafeDestination }
        }
        guard let values = try? root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
              values.isDirectory == true, values.isSymbolicLink != true,
              let attributes = try? fileManager.attributesOfItem(atPath: root.path),
              let owner = attributes[.ownerAccountID] as? NSNumber,
              owner.uint32Value == Darwin.getuid()
        else { throw DesktopReleaseDownloadError.unsafeDestination }
        try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
        return root
    }

    private static func validHTTPSURL(_ url: URL) -> Bool {
        url.scheme == "https" && url.host != nil
            && url.user == nil && url.password == nil
            && url.query == nil && url.fragment == nil
    }
}

private final class BoundedNoRedirectDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let maximumBytes: Int64
    private var exceeded = false

    init(maximumBytes: Int64) { self.maximumBytes = maximumBytes }

    var didExceedLimit: Bool { lock.withLock { exceeded } }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        if totalBytesWritten > maximumBytes {
            lock.withLock { exceeded = true }
            downloadTask.cancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {}
}
