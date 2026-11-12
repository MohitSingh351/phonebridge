import CryptoKit
import Foundation
import PBProtocol

// Port of Android `TransferEngine` (feature/files). Same states, same rules — keep the two in step.

public protocol FileSource: AnyObject {
    var size: Int64 { get }
    func read(offset: Int64, max: Int) -> Data
    func close()
}

public protocol FileSink: AnyObject {
    /// Persisted so the sink can be reopened after a restart.
    var ref: String { get }
    func write(offset: Int64, data: Data) throws
    func sync()
    func sha256() -> String
    /// Finalise under a unique name; returns the saved name.
    func commit() throws -> String
    func discard()
    func close()
}

public enum FileIOError: Error { case insufficientSpace, unavailable }

public protocol FileIO: AnyObject {
    func openSource(ref: String) -> FileSource?
    func createSink(offer: FileOffer) throws -> FileSink
    func reopenSink(ref: String, name: String, offset: Int64) -> FileSink?
}

public protocol TransferWire: AnyObject {
    func json<P: MessagePayload>(_ payload: P)
    func binary(_ frame: Data)
}

public protocol TransferStore: AnyObject {
    func load() -> [TransferRecord]
    func save(_ records: [TransferRecord])
}

public enum Direction: String, Codable, Sendable { case OUT, IN }

public enum TransferState: String, Codable, Sendable {
    case WAITING, ACTIVE, PAUSED, VERIFYING, DONE, FAILED, CANCELLED
    public var isFinal: Bool { self == .DONE || self == .FAILED || self == .CANCELLED }
}

public enum PausedBy: String, Codable, Sendable { case LOCAL, REMOTE }

public struct TransferRecord: Codable, Sendable, Identifiable, Equatable {
    public var id: String
    public var direction: Direction
    public var name: String
    public var size: Int64
    public var mime: String
    public var sha256: String
    public var state: TransferState
    public var pausedBy: PausedBy?
    /// OUT: bytes acknowledged. IN: bytes durably written.
    public var offset: Int64 = 0
    public var ref: String?
    public var savedName: String?
    public var error: String?
    public var createdAt: Int64
    public var finishedAt: Int64?
    public var transferred: Int64 = 0
    public var bytesPerSec: Int64 = 0
}

/// Transfer state machine for one side (PROTOCOL.md §9). NOT thread-safe: the caller confines every call to
/// one serial queue.
public final class TransferEngine {
    private let io: FileIO
    private let store: TransferStore
    private let clock: () -> Int64
    private let chunkSize: Int
    private let window: Int
    private let stallMs: Int64
    private let maxRetries: Int
    private let verifyTimeoutMs: Int64
    private let syncEveryBytes: Int64
    private let historyLimit: Int
    private let onChange: ([TransferRecord]) -> Void
    private let log: (String) -> Void

    private var order: [String] = []
    private var records: [String: TransferRecord] = [:]
    private weak var wire: TransferWire?

    private final class Out { var source: FileSource?; var next: Int64 = 0; var lastProgress: Int64 = 0; var retries = 0; var retryAt: Int64 = 0; var doneSentAt: Int64 = 0 }
    private final class In { var sink: FileSink?; var expected: Int64 = 0; var synced: Int64 = 0; var hasher: SHA256?; var lastNack: Int64 = 0 }
    private var outs: [String: Out] = [:]
    private var ins: [String: In] = [:]
    private var rate: [String: (Int64, Int64)] = [:]
    private var lastNotify: Int64 = 0

    public init(io: FileIO, store: TransferStore, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                chunkSize: Int = PBConstants.maxChunkPayload, window: Int = 8, stallMs: Int64 = 10_000, maxRetries: Int = 5,
                verifyTimeoutMs: Int64 = 120_000, syncEveryBytes: Int64 = 4 << 20, historyLimit: Int = 200,
                onChange: @escaping ([TransferRecord]) -> Void = { _ in }, log: @escaping (String) -> Void = { _ in }) {
        self.log = log
        self.io = io; self.store = store; self.clock = clock; self.chunkSize = chunkSize; self.window = window
        self.stallMs = stallMs; self.maxRetries = maxRetries; self.verifyTimeoutMs = verifyTimeoutMs
        self.syncEveryBytes = syncEveryBytes; self.historyLimit = historyLimit; self.onChange = onChange
        for var r in store.load() {
            if !r.state.isFinal && r.state != .PAUSED { r.state = .WAITING }
            records[r.id] = r; order.append(r.id)
        }
    }

    public var all: [TransferRecord] { order.compactMap { records[$0] } }
    public func get(_ id: String) -> TransferRecord? { records[id] }

    // MARK: Session

    public func sessionStarted(_ wire: TransferWire) {
        log("session started")
        self.wire = wire
        for r in all where r.direction == .OUT && !r.state.isFinal {
            if r.state == .PAUSED && r.pausedBy == .LOCAL { continue }
            sendOffer(r)
        }
    }

    public func sessionEnded() {
        log("session ended")
        wire = nil
        for r in all {
            if r.state == .ACTIVE || r.state == .VERIFYING { var w = r; w.state = .WAITING; update(w) }
            if r.direction == .IN, let st = ins[r.id] { syncIn(r.id, st) }
        }
        persist()
    }

    // MARK: User actions

    @discardableResult
    public func send(sourceRef: String, name: String, size: Int64, mime: String, sha256: String) -> String {
        let id = UUID().uuidString.lowercased()
        let r = TransferRecord(id: id, direction: .OUT, name: name, size: size, mime: mime, sha256: sha256, state: .WAITING,
                               ref: sourceRef, createdAt: clock())
        records[id] = r; order.append(id)
        persist()
        if wire != nil { sendOffer(r) } else { notifyChange() }
        return id
    }

    public func pause(_ id: String) {
        guard var r = records[id], !r.state.isFinal, r.state != .PAUSED else { return }
        wire?.json(FilePause(transferId: pbid(id)))
        if r.direction == .IN, let st = ins[id] { syncIn(id, st); r = records[id]! }
        r.state = .PAUSED; r.pausedBy = .LOCAL
        update(r); persist()
    }

    public func resume(_ id: String) {
        guard var r = records[id], r.state == .PAUSED else { return }
        r.state = .WAITING; r.pausedBy = nil
        update(r); persist()
        guard wire != nil else { return }
        if r.direction == .OUT { sendOffer(r) }
        else if let st = ins[id] { acceptAt(r, st) }
        else { wire?.json(FileResume(transferId: pbid(id))) }
    }

    public func cancel(_ id: String) {
        guard let r = records[id], !r.state.isFinal else { return }
        wire?.json(FileCancel(transferId: pbid(id), reason: "user_cancelled"))
        finishLocal(r, .CANCELLED, nil)
    }

    public func retry(_ id: String) {
        guard var r = records[id], r.direction == .OUT, r.state == .FAILED else { return }
        outs[id] = nil
        r.state = .WAITING; r.error = nil; r.finishedAt = nil
        update(r); persist()
        if wire != nil { sendOffer(r) }
    }

    public func clearHistory() {
        for r in all where r.state.isFinal { records[r.id] = nil }
        order.removeAll { records[$0] == nil }
        persist(); notifyChange()
    }

    // MARK: Inbound

    public func received(_ env: Envelope) {
        do {
            switch env.type {
            case FileOffer.type: onOffer(try env.decode(FileOffer.self))
            case FileAccept.type: onAccept(try env.decode(FileAccept.self))
            case FileReject.type:
                let m = try env.decode(FileReject.self)
                if let r = records[m.transferId.description] { finishLocal(r, .FAILED, m.reason.rawValue) }
            case FileAck.type: onAck(try env.decode(FileAck.self))
            case FileNack.type: onNack(try env.decode(FileNack.self))
            case FilePause.type: onRemotePause(try env.decode(FilePause.self).transferId.description)
            case FileResume.type: onRemoteResume(try env.decode(FileResume.self).transferId.description)
            case FileCancel.type:
                let m = try env.decode(FileCancel.self)
                if let r = records[m.transferId.description], !r.state.isFinal { finishLocal(r, .CANCELLED, "cancelled_by_peer") }
            case FileDone.type: onDone(try env.decode(FileDone.self).transferId.description)
            case FileResult.type: onResult(try env.decode(FileResult.self))
            default: break
            }
        } catch {
            // malformed file message: ignore
        }
    }

    public func receivedBinary(_ bytes: Data) {
        let frame: ChunkFrame
        do { frame = try ChunkFrame.decode(bytes) } catch {
            if case .crcMismatch(let tid, _) = error, let st = ins[tid.description] { nack(tid.description, st) }
            return
        }
        let id = frame.transferId.description
        guard let r = records[id], let st = ins[id], r.state == .ACTIVE else { return }
        if Int64(frame.offset) == st.expected {
            guard let sink = st.sink else { return }
            if st.expected + Int64(frame.payload.count) > r.size { nack(id, st); return }
            do { try sink.write(offset: st.expected, data: frame.payload) } catch {
                failAndTell(r, "io_error"); return // disk full / volume gone: stop cleanly, tell the phone
            }
            st.hasher?.update(data: frame.payload)
            st.expected += Int64(frame.payload.count)
            if st.expected - st.synced >= syncEveryBytes { syncIn(id, st) }
            wire?.json(FileAck(transferId: frame.transferId, offset: st.expected))
            progress(records[id]!, st.expected)
        } else if Int64(frame.offset) > st.expected {
            nack(id, st)
        }
    }

    public func tick() {
        let now = clock()
        for r in all where r.direction == .OUT {
            guard let o = outs[r.id] else { continue }
            switch r.state {
            case .ACTIVE:
                if o.retryAt > 0 {
                    if now >= o.retryAt { o.retryAt = 0; o.next = r.offset; o.lastProgress = now; pump(r.id) }
                } else if now - o.lastProgress > stallMs {
                    o.retries += 1
                    if o.retries > maxRetries { finishLocal(r, .FAILED, "transfer_failed") }
                    else { o.retryAt = now + (Int64(1000) << Int64(o.retries - 1)) }
                }
            case .VERIFYING:
                if now - o.doneSentAt > verifyTimeoutMs { o.doneSentAt = now; wire?.json(FileDone(transferId: pbid(r.id))) }
            default: break
            }
        }
    }

    // MARK: Sender

    private func sendOffer(_ r: TransferRecord) {
        guard let w = wire else { return }
        w.json(FileOffer(transferId: pbid(r.id), name: r.name, size: r.size, mime: r.mime, sha256: r.sha256, chunkSize: chunkSize))
        if r.state != .WAITING { var x = r; x.state = .WAITING; update(x) }
    }

    private func onAccept(_ m: FileAccept) {
        let id = m.transferId.description
        guard var r = records[id], r.direction == .OUT, !r.state.isFinal else { return }
        if r.state == .PAUSED && r.pausedBy == .LOCAL { return }
        let o = outs[id] ?? Out(); outs[id] = o
        if o.source == nil {
            o.source = r.ref.flatMap { io.openSource(ref: $0) }
            if o.source == nil { failAndTell(r, "source_unavailable"); return }
        }
        let start = min(max(m.offset, 0), r.size)
        o.next = start; o.retries = 0; o.retryAt = 0; o.lastProgress = clock()
        r.state = .ACTIVE; r.pausedBy = nil; r.offset = start; r.transferred = start
        update(r); persist()
        pump(id)
    }

    private func onAck(_ m: FileAck) {
        let id = m.transferId.description
        guard let r = records[id], let o = outs[id], r.state == .ACTIVE else { return }
        if m.offset > r.offset && m.offset <= r.size {
            o.lastProgress = clock(); o.retries = 0; o.retryAt = 0
            if o.next < m.offset { o.next = m.offset }
            progress(r, m.offset, ackOffset: m.offset)
            pump(id)
        }
    }

    private func onNack(_ m: FileNack) {
        let id = m.transferId.description
        guard let r = records[id], let o = outs[id], r.state == .ACTIVE else { return }
        o.next = min(max(m.offset, r.offset), r.size)
        o.lastProgress = clock()
        pump(id)
    }

    private func pump(_ id: String) {
        guard let r = records[id], let o = outs[id], let src = o.source, let w = wire,
              r.state == .ACTIVE, o.retryAt == 0 else { return }
        let limit = Int64(window * chunkSize)
        while o.next < r.size && o.next - records[id]!.offset < limit {
            let data = src.read(offset: o.next, max: Int(min(Int64(chunkSize), r.size - o.next)))
            if data.isEmpty { failAndTell(r, "source_read_failed"); return }
            w.binary(ChunkFrame(transferId: pbid(id), offset: UInt64(o.next), payload: data).encode())
            o.next += Int64(data.count)
        }
        var cur = records[id]!
        if cur.offset >= cur.size {
            o.doneSentAt = clock()
            w.json(FileDone(transferId: pbid(id)))
            cur.state = .VERIFYING
            update(cur)
        }
    }

    private func onResult(_ m: FileResult) {
        guard var r = records[m.transferId.description], r.direction == .OUT, !r.state.isFinal else { return }
        if m.ok {
            r.savedName = m.savedName; r.offset = r.size; r.transferred = r.size
            finishLocal(r, .DONE, nil)
        } else {
            r.offset = 0; r.transferred = 0
            finishLocal(r, .FAILED, m.error?.rawValue ?? "verify_failed")
        }
    }

    private func onRemotePause(_ id: String) {
        guard var r = records[id], !r.state.isFinal else { return }
        if r.direction == .IN, let st = ins[id] { syncIn(id, st); r = records[id]! }
        r.state = .PAUSED; r.pausedBy = r.pausedBy ?? .REMOTE
        update(r); persist()
    }

    private func onRemoteResume(_ id: String) {
        guard var r = records[id], r.state == .PAUSED, r.pausedBy != .LOCAL else { return }
        r.state = .WAITING; r.pausedBy = nil
        update(r)
        if r.direction == .IN { if let st = ins[id] { acceptAt(r, st) } } else { sendOffer(r) }
    }

    // MARK: Receiver

    private func onOffer(_ offer: FileOffer) {
        let id = offer.transferId.description
        log("offer \(id.prefix(8)) existing=\(records[id]?.state.rawValue ?? "nil") expected=\(ins[id].map { String($0.expected) } ?? "nil") wire=\(wire != nil)")
        guard let w = wire else { return }
        if var existing = records[id] {
            switch existing.state {
            case .DONE: w.json(FileResult(transferId: offer.transferId, ok: true, savedName: existing.savedName))
            case .CANCELLED: w.json(FileCancel(transferId: offer.transferId, reason: "cancelled"))
            case .PAUSED where existing.pausedBy == .LOCAL: w.json(FilePause(transferId: offer.transferId))
            default:
                let st = ins[id] ?? In(); ins[id] = st
                if st.sink == nil || existing.state == .FAILED {
                    let from: Int64 = existing.state == .FAILED ? 0 : existing.offset
                    st.sink?.close()
                    st.sink = existing.ref.flatMap { io.reopenSink(ref: $0, name: existing.name, offset: from) }
                        ?? (try? io.createSink(offer: offer))
                    st.expected = (existing.ref != nil && st.sink?.ref == existing.ref) ? from : 0
                    st.synced = st.expected
                    st.hasher = st.expected == 0 ? SHA256() : nil
                }
                guard let sink = st.sink else { w.json(FileReject(transferId: offer.transferId, reason: .busy)); return }
                existing.state = .WAITING; existing.pausedBy = nil; existing.error = nil; existing.finishedAt = nil
                existing.ref = sink.ref; existing.offset = st.synced
                update(existing)
                acceptAt(existing, st)
            }
            return
        }
        let sink: FileSink
        do { sink = try io.createSink(offer: offer) } catch FileIOError.insufficientSpace {
            w.json(FileReject(transferId: offer.transferId, reason: .insufficientSpace)); return
        } catch {
            w.json(FileReject(transferId: offer.transferId, reason: .busy)); return
        }
        let st = In(); st.sink = sink; st.hasher = SHA256()
        ins[id] = st
        let r = TransferRecord(id: id, direction: .IN, name: offer.name, size: offer.size, mime: offer.mime, sha256: offer.sha256,
                               state: .WAITING, ref: sink.ref, createdAt: clock())
        records[id] = r; order.append(id)
        acceptAt(r, st)
    }

    private func acceptAt(_ r: TransferRecord, _ st: In) {
        log("acceptAt \(r.id.prefix(8)) expected=\(st.expected) synced=\(st.synced)")
        syncIn(r.id, st)
        var x = records[r.id] ?? r
        x.state = .ACTIVE; x.offset = st.synced; x.transferred = st.expected
        update(x); persist()
        wire?.json(FileAccept(transferId: pbid(r.id), offset: st.expected))
    }

    private func onDone(_ id: String) {
        guard var r = records[id], let w = wire, r.direction == .IN else { return }
        if r.state == .DONE { w.json(FileResult(transferId: pbid(id), ok: true, savedName: r.savedName)); return }
        guard r.state == .ACTIVE, let st = ins[id], let sink = st.sink else { return }
        r.state = .VERIFYING; update(r)
        sink.sync()
        let sizeOk = st.expected == r.size
        var hash: String?
        if sizeOk { hash = st.hasher.map { Hex.encode($0.finalize()) } ?? sink.sha256() }
        st.hasher = nil
        if sizeOk && hash == r.sha256 {
            guard let saved = try? sink.commit() else {
                w.json(FileResult(transferId: pbid(id), ok: false, error: .ioError))
                finishLocal(r, .FAILED, "io_error"); return
            }
            ins[id] = nil
            w.json(FileResult(transferId: pbid(id), ok: true, savedName: saved))
            r.savedName = saved; r.offset = r.size; r.transferred = r.size
            finish(r, .DONE, nil)
        } else {
            let err: FileResultError = sizeOk ? .hashMismatch : .sizeMismatch
            sink.discard()
            ins[id] = nil
            w.json(FileResult(transferId: pbid(id), ok: false, error: err))
            r.offset = 0; r.transferred = 0; r.ref = nil
            finish(r, .FAILED, err.rawValue)
        }
    }

    private func nack(_ id: String, _ st: In) {
        let now = clock()
        guard now - st.lastNack >= 1000 else { return }
        st.lastNack = now
        wire?.json(FileNack(transferId: pbid(id), offset: st.expected))
    }

    private func syncIn(_ id: String, _ st: In) {
        guard let sink = st.sink, st.expected != st.synced else { return }
        sink.sync()
        st.synced = st.expected
        if var r = records[id] { r.offset = st.synced; records[id] = r }
        persist()
    }

    // MARK: Helpers

    private func failAndTell(_ r: TransferRecord, _ reason: String) {
        wire?.json(FileCancel(transferId: pbid(r.id), reason: reason))
        finishLocal(r, .FAILED, reason)
    }

    private func finishLocal(_ r: TransferRecord, _ state: TransferState, _ error: String?) {
        if r.direction == .IN {
            if let s = ins.removeValue(forKey: r.id)?.sink { if state == .CANCELLED { s.discard() } else { s.sync(); s.close() } }
        } else {
            outs.removeValue(forKey: r.id)?.source?.close()
        }
        finish(r, state, error)
    }

    private func finish(_ r: TransferRecord, _ state: TransferState, _ error: String?) {
        var x = r
        x.state = state; x.error = error; x.finishedAt = clock(); x.pausedBy = nil; x.bytesPerSec = 0
        records[r.id] = x
        rate[r.id] = nil
        trimHistory(); persist(); notifyChange()
    }

    private func progress(_ r: TransferRecord, _ transferred: Int64, ackOffset: Int64? = nil) {
        let now = clock()
        var x = r
        if let (t0, b0) = rate[r.id] {
            if now - t0 >= 1000 { x.bytesPerSec = (transferred - b0) * 1000 / (now - t0); rate[r.id] = (now, transferred) }
        } else { rate[r.id] = (now, transferred) }
        x.transferred = transferred
        if let a = ackOffset { x.offset = a }
        records[r.id] = x
        if now - lastNotify >= 250 { notifyChange() }
    }

    @discardableResult
    private func update(_ r: TransferRecord) -> TransferRecord {
        if records[r.id] == nil { order.append(r.id) }
        records[r.id] = r
        notifyChange()
        return r
    }

    private func trimHistory() {
        let finished = all.filter(\.state.isFinal).sorted { ($0.finishedAt ?? $0.createdAt) < ($1.finishedAt ?? $1.createdAt) }
        let extra = finished.count - historyLimit
        if extra > 0 { for r in finished.prefix(extra) { records[r.id] = nil } ; order.removeAll { records[$0] == nil } }
    }

    private func persist() { store.save(all) }

    private func notifyChange() { lastNotify = clock(); onChange(all) }

    private func pbid(_ s: String) -> PBUUID { PBUUID(string: s)! }
}
