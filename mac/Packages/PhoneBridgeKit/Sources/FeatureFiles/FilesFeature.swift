import AppKit
import CryptoKit
import Foundation
import Observation
import os
import PBProtocol
import PBTransport
import UniformTypeIdentifiers
@preconcurrency import UserNotifications

/// File transfer, Mac side. The engine runs on its own serial queue (never the main thread), so hashing a
/// large file or writing chunks never stalls the UI or the session.
@MainActor @Observable
public final class FilesFeature: Feature {
    public let id = "files"
    public var state: CapabilityState { .available }

    public private(set) var transfers: [TransferRecord] = []
    public private(set) var connectedPhone: String?
    public let io: MacFileIO

    private let queue = DispatchQueue(label: "dev.phonebridge.files")
    private var engine: TransferEngine!
    private var link: FeatureLink?
    private var wireBox: Wire?
    private var timer: Timer?

    /// Bridges engine output to the live session link.
    private final class Wire: TransferWire, @unchecked Sendable {
        let link: FeatureLink
        init(_ link: FeatureLink) { self.link = link }
        func json<P: MessagePayload>(_ payload: P) { link.send(payload) }
        func binary(_ frame: Data) { link.sendBinary(frame) }
    }

    public init(io: MacFileIO = MacFileIO(), store: TransferStore = JSONTransferStore()) {
        self.io = io
        let box = WeakBox()
        let engine = TransferEngine(io: io, store: store, onChange: Self.changeHandler(box),
                                    log: { Self.logger.info("\($0, privacy: .public)") })
        queue.sync { self.engine = engine }
        box.feature = self
        transfers = engine.all.sorted { $0.createdAt > $1.createdAt }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    // MARK: Feature

    public func sessionStarted(_ link: FeatureLink) {
        self.link = link
        connectedPhone = link.device.name
        let w = Wire(link)
        wireBox = w
        queue.async { self.engine.sessionStarted(w) }
    }

    public func received(_ envelope: Envelope, from link: FeatureLink) {
        queue.async { self.engine.received(envelope) }
    }

    public func receivedBinary(_ frame: Data, from link: FeatureLink) {
        queue.async { self.engine.receivedBinary(frame) }
    }

    public func sessionEnded(_ link: FeatureLink) {
        guard link == self.link else { return }
        self.link = nil; wireBox = nil; connectedPhone = nil
        queue.async { self.engine.sessionEnded() }
    }

    // MARK: User actions

    /// Send files chosen in the picker or dropped on the menu. Hashes each one first, off the main thread.
    public func send(urls: [URL]) {
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            guard let ref = MacFileIO.ref(for: url) else { continue }
            if scoped { url.stopAccessingSecurityScopedResource() }
            queue.async {
                guard let src = self.io.openSource(ref: ref) else { return }
                defer { src.close() }
                var h = SHA256()
                var off: Int64 = 0
                while off < src.size {
                    let d = src.read(offset: off, max: 1 << 20)
                    if d.isEmpty { return }
                    h.update(data: d); off += Int64(d.count)
                }
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                self.engine.send(sourceRef: ref, name: url.lastPathComponent, size: src.size, mime: mime,
                                 sha256: Hex.encode(h.finalize()))
            }
        }
    }

    public func pickAndSend() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send to Phone"
        panel.message = connectedPhone.map { "Send files to \($0)" } ?? "Files will be sent when your phone connects."
        NSApp.activate()
        if panel.runModal() == .OK { send(urls: panel.urls) }
    }

    public func pause(_ id: String) { queue.async { self.engine.pause(id) } }
    public func resume(_ id: String) { queue.async { self.engine.resume(id) } }
    public func cancel(_ id: String) { queue.async { self.engine.cancel(id) } }
    public func retry(_ id: String) { queue.async { self.engine.retry(id) } }
    public func clearHistory() { queue.async { self.engine.clearHistory() } }

    public func reveal(_ r: TransferRecord) {
        guard r.direction == .IN, r.state == .DONE, let name = r.savedName else { return }
        NSWorkspace.shared.activateFileViewerSelecting([io.downloads.appendingPathComponent(name)])
    }

    // MARK: Internals

    private func tick() {
        guard link != nil else { return }
        queue.async { self.engine.tick() }
    }

    private func apply(_ list: [TransferRecord]) {
        let before = Dictionary(uniqueKeysWithValues: transfers.map { ($0.id, $0.state) })
        transfers = list
        for r in list where r.direction == .IN && r.state == .DONE && before[r.id] != nil && before[r.id] != .DONE {
            notifyReceived(r)
        }
    }

    private func notifyReceived(_ r: TransferRecord) {
        Self.postReceived(id: r.id, title: "Received from \(connectedPhone ?? "your phone")",
                          body: "\(r.savedName ?? r.name) — saved to Downloads/PhoneBridge")
    }

    // IMPORTANT: closures handed to the engine or to system APIs run on background threads. Closures written
    // inside this @MainActor class would inherit main-actor isolation and Swift 6's runtime check crashes the
    // app when they're called off the main thread (that bug shipped once — see FilesFeatureTests). So these
    // factories are `nonisolated` and hop to the main actor explicitly.

    final class WeakBox: @unchecked Sendable { weak var feature: FilesFeature? }
    nonisolated static let logger = Logger(subsystem: "dev.phonebridge", category: "files")

    nonisolated static func changeHandler(_ box: WeakBox) -> ([TransferRecord]) -> Void {
        { list in
            let sorted = list.sorted { $0.createdAt > $1.createdAt }
            DispatchQueue.main.async { MainActor.assumeIsolated { box.feature?.apply(sorted) } }
        }
    }

    nonisolated private static func postReceived(id: String, title: String, body: String) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
        }
    }
}

// MARK: - Formatting helpers shared by the UI

public func formatBytes(_ b: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: b, countStyle: .file)
}

public func formatDuration(_ s: Int64) -> String {
    s < 60 ? "\(s)s" : s < 3600 ? "\(s / 60)m \(s % 60)s" : "\(s / 3600)h \((s % 3600) / 60)m"
}
