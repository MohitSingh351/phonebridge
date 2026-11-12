import CryptoKit
import Foundation
import PBProtocol

/// Mac storage for transfers.
///  - Sources are security-scoped bookmarks (from the file picker or a drop), so a transfer can resume even
///    after PhoneBridge restarts, inside the App Sandbox.
///  - Received files are written to ~/Downloads/PhoneBridge/<name>.pbpart and renamed on success, resolving
///    name clashes the Finder way: "photo.jpg" → "photo (1).jpg".
public final class MacFileIO: FileIO {
    public let downloads: URL
    static let reserveBytes: Int64 = 200 << 20

    public init(downloads: URL? = nil) {
        let base = downloads ?? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PhoneBridge", isDirectory: true)
        self.downloads = base
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
    }

    // MARK: Sources

    /// Reference to persist for a user-chosen file.
    public static func ref(for url: URL) -> String? {
        guard let data = try? url.bookmarkData(options: [.withSecurityScope, .securityScopeAllowOnlyReadAccess],
                                               includingResourceValuesForKeys: nil, relativeTo: nil) else {
            return "path:" + url.path // tests / non-sandboxed fallback
        }
        return "bm:" + data.base64EncodedString()
    }

    public func openSource(ref: String) -> FileSource? {
        let url: URL
        var scoped = false
        if ref.hasPrefix("bm:"), let data = Data(base64Encoded: String(ref.dropFirst(3))) {
            var stale = false
            guard let u = try? URL(resolvingBookmarkData: data, options: .withSecurityScope, relativeTo: nil,
                                   bookmarkDataIsStale: &stale) else { return nil }
            scoped = u.startAccessingSecurityScopedResource()
            url = u
        } else if ref.hasPrefix("path:") {
            url = URL(fileURLWithPath: String(ref.dropFirst(5)))
        } else { return nil }
        guard let h = try? FileHandle(forReadingFrom: url),
              let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value else {
            if scoped { url.stopAccessingSecurityScopedResource() }
            return nil
        }
        return HandleSource(handle: h, size: size, url: url, scoped: scoped)
    }

    // MARK: Sinks

    public func createSink(offer: FileOffer) throws -> FileSink {
        let free = (try? downloads.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage) ?? Int64.max
        guard free >= offer.size + Self.reserveBytes else { throw FileIOError.insufficientSpace }
        let part = downloads.appendingPathComponent(".\(offer.transferId).\(offer.name).pbpart")
        FileManager.default.createFile(atPath: part.path, contents: nil)
        return try PartSink(part: part, finalName: offer.name, dir: downloads, offset: 0)
    }

    public func reopenSink(ref: String, name: String, offset: Int64) -> FileSink? {
        let part = URL(fileURLWithPath: ref)
        guard FileManager.default.fileExists(atPath: part.path) else { return nil }
        return try? PartSink(part: part, finalName: name, dir: downloads, offset: offset)
    }

    /// "photo.jpg" → "photo (1).jpg" → "photo (2).jpg" … until free.
    public static func uniqueName(_ name: String, in dir: URL, fm: FileManager = .default) -> String {
        guard fm.fileExists(atPath: dir.appendingPathComponent(name).path) else { return name }
        let ext = (name as NSString).pathExtension
        let base = (name as NSString).deletingPathExtension
        var i = 1
        while true {
            let candidate = ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)"
            if !fm.fileExists(atPath: dir.appendingPathComponent(candidate).path) { return candidate }
            i += 1
        }
    }
}

private final class HandleSource: FileSource {
    let handle: FileHandle
    let size: Int64
    let url: URL
    let scoped: Bool
    init(handle: FileHandle, size: Int64, url: URL, scoped: Bool) {
        self.handle = handle; self.size = size; self.url = url; self.scoped = scoped
    }
    func read(offset: Int64, max: Int) -> Data {
        do {
            try handle.seek(toOffset: UInt64(offset))
            return try handle.read(upToCount: max) ?? Data()
        } catch { return Data() }
    }
    func close() {
        try? handle.close()
        if scoped { url.stopAccessingSecurityScopedResource() }
    }
}

private final class PartSink: FileSink {
    let part: URL
    let finalName: String
    let dir: URL
    let handle: FileHandle
    var ref: String { part.path }

    init(part: URL, finalName: String, dir: URL, offset: Int64) throws {
        self.part = part; self.finalName = finalName; self.dir = dir
        handle = try FileHandle(forUpdating: part)
        try handle.truncate(atOffset: UInt64(offset)) // drop anything after the last durable point
    }

    func write(offset: Int64, data: Data) throws {
        try handle.seek(toOffset: UInt64(offset))
        try handle.write(contentsOf: data)
    }

    func sync() { try? handle.synchronize() }

    func sha256() -> String {
        var h = SHA256()
        guard let r = try? FileHandle(forReadingFrom: part) else { return "" }
        defer { try? r.close() }
        while let d = try? r.read(upToCount: 1 << 20), !d.isEmpty { h.update(data: d) }
        return Hex.encode(h.finalize())
    }

    func commit() throws -> String {
        sync(); close()
        let name = MacFileIO.uniqueName(finalName, in: dir)
        try FileManager.default.moveItem(at: part, to: dir.appendingPathComponent(name))
        return name
    }

    func discard() { close(); try? FileManager.default.removeItem(at: part) }
    func close() { try? handle.close() }
}

/// Transfer records as a small JSON file in Application Support (see the plan's decision log).
public final class JSONTransferStore: TransferStore {
    let url: URL
    public init(url: URL? = nil) {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PhoneBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.url = url ?? dir.appendingPathComponent("transfers.json")
    }
    public func load() -> [TransferRecord] {
        (try? JSONDecoder().decode([TransferRecord].self, from: Data(contentsOf: url))) ?? []
    }
    public func save(_ records: [TransferRecord]) {
        if let d = try? JSONEncoder().encode(records) { try? d.write(to: url, options: .atomic) }
    }
}
