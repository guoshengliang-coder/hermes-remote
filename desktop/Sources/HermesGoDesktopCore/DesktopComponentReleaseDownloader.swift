import CryptoKit
import Darwin
import Foundation

public enum DesktopComponentDownloadError: Error, Equatable, Sendable {
    case invalidRequest
    case invalidResponse
    case responseTooLarge
    case unsafeDestination
    case transportFailed
    case digestMismatch
}

/// Downloads one signed v2 component into an owner-only staging directory. A partial file is kept
/// after a transport interruption and resumed with an exact Range request. Completion always
/// re-reads the entire file and checks both signed size and SHA-256 before exposing the final name.
public final class DesktopComponentReleaseDownloader: @unchecked Sendable {
    private let configuration: URLSessionConfiguration
    private let fileManager: FileManager

    public init(
        configuration: URLSessionConfiguration = .ephemeral,
        fileManager: FileManager = .default
    ) {
        let copy = configuration.copy() as! URLSessionConfiguration
        copy.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        copy.urlCache = nil
        copy.timeoutIntervalForRequest = 60
        copy.timeoutIntervalForResource = 15 * 60
        copy.waitsForConnectivity = false
        self.configuration = copy
        self.fileManager = fileManager
    }

    public func download(
        _ component: DesktopComponentReleaseArtifactV2,
        into downloadRoot: URL
    ) async throws -> URL {
        guard let url = URL(string: component.downloadURL),
              Self.validHTTPSURL(url),
              component.fileName == url.lastPathComponent,
              component.sizeBytes > 0,
              component.sizeBytes <= 2 * 1024 * 1024 * 1024,
              component.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
        else { throw DesktopComponentDownloadError.invalidRequest }
        let root = try preparePrivateRoot(downloadRoot)
        let destination = root.appendingPathComponent(component.fileName)
        let partial = root.appendingPathComponent(".\(component.fileName).partial")
        guard !fileManager.fileExists(atPath: destination.path) else {
            throw DesktopComponentDownloadError.unsafeDestination
        }
        let offset = try preparePartial(partial, maximum: component.sizeBytes)
        if offset == component.sizeBytes {
            return try commitVerifiedPartial(
                partial, destination: destination, size: component.sizeBytes, sha256: component.sha256
            )
        }

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData)
        if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let delegate = ComponentStreamingDownloadDelegate(
                requestedURL: url,
                partialURL: partial,
                startingOffset: offset,
                expectedSize: component.sizeBytes,
                continuation: continuation
            )
            let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
            delegate.retain(session: session)
            session.dataTask(with: request).resume()
        }
        return try commitVerifiedPartial(
            partial, destination: destination, size: component.sizeBytes, sha256: component.sha256
        )
    }

    private func preparePartial(_ url: URL, maximum: Int64) throws -> Int64 {
        if !fileManager.fileExists(atPath: url.path) {
            guard fileManager.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
                throw DesktopComponentDownloadError.unsafeDestination
            }
            return 0
        }
        let standardized = url.standardizedFileURL
        let resolved = standardized.resolvingSymlinksInPath()
        guard standardized == resolved,
              let values = try? resolved.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
              values.isRegularFile == true,
              values.isSymbolicLink != true,
              let attributes = try? fileManager.attributesOfItem(atPath: resolved.path),
              let owner = attributes[.ownerAccountID] as? NSNumber,
              owner.uint32Value == Darwin.getuid(),
              let size = attributes[.size] as? NSNumber,
              size.int64Value >= 0,
              size.int64Value <= maximum
        else { throw DesktopComponentDownloadError.unsafeDestination }
        try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: resolved.path)
        return size.int64Value
    }

    private func commitVerifiedPartial(
        _ partial: URL,
        destination: URL,
        size: Int64,
        sha256 expectedSHA256: String
    ) throws -> URL {
        guard let attributes = try? fileManager.attributesOfItem(atPath: partial.path),
              let actualSize = attributes[.size] as? NSNumber,
              actualSize.int64Value == size
        else { throw DesktopComponentDownloadError.invalidResponse }
        guard try sha256(partial) == expectedSHA256 else {
            try? fileManager.removeItem(at: partial)
            throw DesktopComponentDownloadError.digestMismatch
        }
        do {
            try fileManager.moveItem(at: partial, to: destination)
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
            return destination
        } catch {
            try? fileManager.removeItem(at: destination)
            throw DesktopComponentDownloadError.unsafeDestination
        }
    }

    private func sha256(_ url: URL) throws -> String {
        let handle: FileHandle
        do { handle = try FileHandle(forReadingFrom: url) }
        catch { throw DesktopComponentDownloadError.unsafeDestination }
        defer { try? handle.close() }
        var digest = SHA256()
        do {
            while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
                digest.update(data: data)
            }
        } catch { throw DesktopComponentDownloadError.unsafeDestination }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func preparePrivateRoot(_ value: URL) throws -> URL {
        let supplied = value.standardizedFileURL
        let root = supplied.resolvingSymlinksInPath()
        guard supplied == root,
              root.isFileURL, root.path.hasPrefix("/"), root.path != "/" else {
            throw DesktopComponentDownloadError.unsafeDestination
        }
        if !fileManager.fileExists(atPath: root.path) {
            do {
                try fileManager.createDirectory(
                    at: root, withIntermediateDirectories: true,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch { throw DesktopComponentDownloadError.unsafeDestination }
        }
        guard let values = try? root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
              values.isDirectory == true, values.isSymbolicLink != true,
              let attributes = try? fileManager.attributesOfItem(atPath: root.path),
              let owner = attributes[.ownerAccountID] as? NSNumber,
              owner.uint32Value == Darwin.getuid()
        else { throw DesktopComponentDownloadError.unsafeDestination }
        try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
        return root
    }

    private static func validHTTPSURL(_ url: URL) -> Bool {
        url.scheme == "https" && url.host != nil && url.user == nil && url.password == nil
            && url.query == nil && url.fragment == nil
    }
}

private final class ComponentStreamingDownloadDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let requestedURL: URL
    private let partialURL: URL
    private let startingOffset: Int64
    private let expectedSize: Int64
    private var receivedBytes: Int64 = 0
    private var failure: DesktopComponentDownloadError?
    private var continuation: CheckedContinuation<Void, Error>?
    private var retainedSession: URLSession?
    private var handle: FileHandle?

    init(
        requestedURL: URL,
        partialURL: URL,
        startingOffset: Int64,
        expectedSize: Int64,
        continuation: CheckedContinuation<Void, Error>
    ) {
        self.requestedURL = requestedURL
        self.partialURL = partialURL
        self.startingOffset = startingOffset
        self.expectedSize = expectedSize
        self.continuation = continuation
    }

    func retain(session: URLSession) { lock.withLock { retainedSession = session } }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        lock.withLock { failure = .invalidResponse }
        completionHandler(nil)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let valid = lock.withLock { () -> Bool in
            guard failure == nil,
                  let http = response as? HTTPURLResponse,
                  http.url == requestedURL
            else { return false }
            let remaining = expectedSize - startingOffset
            if startingOffset == 0 {
                guard http.statusCode == 200 else { return false }
            } else {
                guard http.statusCode == 206,
                      http.value(forHTTPHeaderField: "Content-Range")
                        == "bytes \(startingOffset)-\(expectedSize - 1)/\(expectedSize)"
                else { return false }
            }
            if let value = http.value(forHTTPHeaderField: "Content-Length"),
               Int64(value) != remaining { return false }
            do {
                handle = try FileHandle(forWritingTo: partialURL)
                try handle?.seek(toOffset: UInt64(startingOffset))
            } catch { return false }
            return true
        }
        if !valid { lock.withLock { failure = .invalidResponse } }
        completionHandler(valid ? .allow : .cancel)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let shouldCancel = lock.withLock { () -> Bool in
            guard failure == nil, startingOffset + receivedBytes + Int64(data.count) <= expectedSize else {
                failure = .responseTooLarge
                return true
            }
            guard let handle else {
                failure = .unsafeDestination
                return true
            }
            do {
                try handle.write(contentsOf: data)
                receivedBytes += Int64(data.count)
                return false
            } catch {
                failure = .unsafeDestination
                return true
            }
        }
        if shouldCancel { dataTask.cancel() }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        let result = lock.withLock { () -> (CheckedContinuation<Void, Error>?, Error?) in
            try? handle?.close()
            handle = nil
            let finalError: Error?
            if let failure { finalError = failure }
            else if error != nil { finalError = DesktopComponentDownloadError.transportFailed }
            else if startingOffset + receivedBytes != expectedSize {
                finalError = DesktopComponentDownloadError.invalidResponse
            } else { finalError = nil }
            let saved = continuation
            continuation = nil
            retainedSession = nil
            return (saved, finalError)
        }
        session.finishTasksAndInvalidate()
        if let error = result.1 { result.0?.resume(throwing: error) }
        else { result.0?.resume() }
    }
}
