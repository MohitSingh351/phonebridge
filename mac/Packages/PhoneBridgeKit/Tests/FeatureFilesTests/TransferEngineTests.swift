import CryptoKit
import Foundation
import PBProtocol
import Testing
@testable import FeatureFiles

// Mac port of the Android TransferEngineTest harness: two engines joined by an in-memory network.

final class GenSource: FileSource {
    let size: Int64; let seed: Int64
    var corruptAt: Int64 = -1
    init(_ size: Int64, seed: Int64) { self.size = size; self.seed = seed }
    func read(offset: Int64, max: Int) -> Data {
        let n = Int(min(Int64(max), size - offset))
        var d = Data(count: n)
        d.withUnsafeMutableBytes { p in
            for i in 0..<n {
                let pos = offset + Int64(i)
                p[i] = pos == corruptAt ? 0x55 : UInt8(truncatingIfNeeded: (pos &* 31 &+ seed) ^ (pos >> 8))
            }
        }
        return d
    }
    func close() {}
    func sha256() -> String {
        let saved = corruptAt; corruptAt = -1; defer { corruptAt = saved }
        var h = SHA256(); var off: Int64 = 0
        while off < size { let d = read(offset: off, max: 1 << 20); h.update(data: d); off += Int64(d.count) }
        return Hex.encode(h.finalize())
    }
}

final class MemSink: FileSink {
    let ref: String; let io: MemIO
    var bytes = Data(); var durable = 0
    init(_ ref: String, _ io: MemIO) { self.ref = ref; self.io = io }
    func write(offset: Int64, data: Data) throws { precondition(Int(offset) == bytes.count); bytes.append(data) }
    func sync() { durable = bytes.count }
    func sha256() -> String { Hex.encode(SHA256.hash(data: bytes)) }
    func commit() throws -> String { io.commit(self) }
    func discard() { io.sinks[ref] = nil; io.discarded.append(ref) }
    func close() {}
    func crash() { bytes = bytes.prefix(durable) }
}

final class MemIO: FileIO {
    var sources: [String: GenSource] = [:]
    var sinks: [String: MemSink] = [:]
    var saved: [String] = []
    var discarded: [String] = []
    var free: Int64 = .max
    var n = 0
    func openSource(ref: String) -> FileSource? { sources[ref] }
    func createSink(offer: FileOffer) throws -> FileSink {
        if offer.size > free { throw FileIOError.insufficientSpace }
        n += 1
        let s = MemSink("sink\(n)|\(offer.name)", self); sinks[s.ref] = s; return s
    }
    func reopenSink(ref: String, name: String, offset: Int64) -> FileSink? {
        guard let s = sinks[ref] else { return nil }
        if s.bytes.count > Int(offset) { s.crash(); s.bytes = s.bytes.prefix(Int(offset)) }
        return s
    }
    func commit(_ s: MemSink) -> String {
        let base = String(s.ref.split(separator: "|")[1])
        var name = base; var i = 1
        while saved.contains(name) {
            name = (base as NSString).deletingPathExtension + " (\(i))." + (base as NSString).pathExtension; i += 1
        }
        saved.append(name); return name
    }
}

final class MemStore: TransferStore {
    var records: [TransferRecord] = []
    func load() -> [TransferRecord] { records }
    func save(_ r: [TransferRecord]) { records = r }
}

final class Net {
    enum Payload { case json(Data), bin(Data) }
    struct Frame { let to: Side; let p: Payload }

    final class Side: TransferWire {
        let io = MemIO(); let store = MemStore()
        var engine: TransferEngine!
        let seq = OutboundSequence()
        weak var net: Net?
        var peer: Side!
        var mutableSeq = OutboundSequence()
        func json<P: MessagePayload>(_ payload: P) {
            let env = try! Envelope.make(payload, seq: mutableSeq.take())
            net?.queue.append(Frame(to: peer, p: .json(try! EnvelopeCodec.encode(env))))
        }
        func binary(_ frame: Data) { net?.queue.append(Frame(to: peer, p: .bin(frame))) }
        func restart(_ clock: @escaping () -> Int64) { engine = TransferEngine(io: io, store: store, clock: clock) }
    }

    var queue: [Frame] = []
    var connected = false
    var now: Int64 = 1_000_000
    let phone = Side(), mac = Side()
    var filter: (Frame) -> Frame? = { $0 }

    init() {
        for s in [phone, mac] { s.net = self; s.restart { [unowned self] in self.now } }
        phone.peer = mac; mac.peer = phone
    }
    func connect() { connected = true; mac.engine.sessionStarted(mac); phone.engine.sessionStarted(phone) }
    func disconnect() { connected = false; queue.removeAll(); phone.engine.sessionEnded(); mac.engine.sessionEnded() }
    @discardableResult func deliver(_ max: Int = .max) -> Int {
        var n = 0
        while connected, !queue.isEmpty, n < max {
            guard let f = filter(queue.removeFirst()) else { continue }
            n += 1
            switch f.p {
            case .json(let d): f.to.engine.received(try! EnvelopeCodec.decode(d))
            case .bin(let d): f.to.engine.receivedBinary(d)
            }
        }
        return n
    }
}

@Suite("Mac transfer engine")
struct TransferEngineTests {
    func offer(_ net: Net, _ size: Int64, name: String = "file.bin", seed: Int64 = 7) -> (String, GenSource) {
        let src = GenSource(size, seed: seed)
        let ref = "src:\(name):\(seed):\(net.phone.io.sources.count)"
        net.phone.io.sources[ref] = src
        return (net.phone.engine.send(sourceRef: ref, name: name, size: size, mime: "application/octet-stream", sha256: src.sha256()), src)
    }

    @Test(arguments: [Int64(0), 1, 262_143, 262_144, 262_145, 3_000_000])
    func edgeSizes(_ size: Int64) {
        let net = Net(); net.connect()
        let (id, _) = offer(net, size)
        net.deliver()
        #expect(net.phone.engine.get(id)?.state == .DONE)
        #expect(net.mac.engine.get(id)?.savedName == "file.bin")
    }

    @Test func boundedWindow() {
        let net = Net(); net.connect()
        let (id, _) = offer(net, 32 << 20)
        var maxInFlight = 0
        while !net.queue.isEmpty {
            maxInFlight = max(maxInFlight, net.queue.filter { if case .bin = $0.p { true } else { false } }.count)
            net.deliver(1)
        }
        #expect(net.mac.engine.get(id)?.state == .DONE)
        #expect(maxInFlight <= 8)
    }

    @Test(arguments: [10, 50, 99])
    func resumeAfterCut(_ percent: Int) {
        let net = Net(); net.connect()
        let size: Int64 = 20 << 20
        let (id, _) = offer(net, size)
        while (net.mac.engine.get(id)?.transferred ?? 0) < size * Int64(percent) / 100 { net.deliver(1) }
        net.disconnect()
        #expect(net.phone.engine.get(id)?.state == .WAITING)
        net.connect(); net.deliver()
        #expect(net.mac.engine.get(id)?.state == .DONE)
    }

    @Test func receiverRestart() {
        let net = Net(); net.connect()
        let (id, _) = offer(net, 20 << 20)
        while (net.mac.engine.get(id)?.transferred ?? 0) < 12 << 20 { net.deliver(1) }
        net.disconnect()
        net.mac.io.sinks.values.first!.crash()
        net.mac.restart { net.now }
        #expect((net.mac.engine.get(id)?.offset ?? 0) >= 8 << 20)
        net.connect(); net.deliver()
        #expect(net.mac.engine.get(id)?.state == .DONE)
        #expect(net.phone.engine.get(id)?.state == .DONE)
    }

    @Test func corruptedChunkIsResent() {
        let net = Net()
        var corrupted = 0
        net.filter = { f in
            if case .bin(var d) = f.p, corrupted == 0, (try? ChunkFrame.decode(d))?.offset == 262_144 {
                corrupted += 1; d[d.count - 1] ^= 0xFF; return Net.Frame(to: f.to, p: .bin(d))
            }
            return f
        }
        net.connect()
        let (id, _) = offer(net, 2 << 20)
        net.deliver()
        for _ in 0..<15 { net.now += 1000; net.phone.engine.tick(); net.deliver() }
        #expect(corrupted == 1)
        #expect(net.mac.engine.get(id)?.state == .DONE)
    }

    @Test func stallFailThenRetry() {
        let net = Net(); net.connect()
        let (id, _) = offer(net, 1 << 20)
        net.filter = { f in if case .bin = f.p { return nil }; return f }
        net.deliver()
        for _ in 0..<120 { net.now += 1000; net.phone.engine.tick(); net.deliver() }
        #expect(net.phone.engine.get(id)?.state == .FAILED)
        net.filter = { $0 }
        net.phone.engine.retry(id); net.deliver()
        #expect(net.mac.engine.get(id)?.state == .DONE)
    }

    @Test func pauseResumeBothSides() {
        let net = Net(); net.connect()
        let (a, _) = offer(net, 8 << 20, name: "a.bin")
        net.deliver(10)
        net.phone.engine.pause(a); net.deliver()
        #expect(net.mac.engine.get(a)?.state == .PAUSED)
        net.phone.engine.resume(a); net.deliver()
        #expect(net.mac.engine.get(a)?.state == .DONE)

        let (b, _) = offer(net, 8 << 20, name: "b.bin", seed: 3)
        net.deliver(10)
        net.mac.engine.pause(b); net.deliver()
        #expect(net.phone.engine.get(b)?.state == .PAUSED)
        net.mac.engine.resume(b); net.deliver()
        #expect(net.phone.engine.get(b)?.state == .DONE)
    }

    @Test func cancelDiscardsPartial() {
        let net = Net(); net.connect()
        let (id, _) = offer(net, 8 << 20)
        net.deliver(10)
        net.mac.engine.cancel(id); net.deliver()
        #expect(net.phone.engine.get(id)?.state == .CANCELLED)
        #expect(net.mac.io.discarded.count == 1)
    }

    @Test func hashMismatch() {
        let net = Net(); net.connect()
        let (id, src) = offer(net, 1 << 20)
        src.corruptAt = 999
        net.deliver()
        #expect(net.phone.engine.get(id)?.error == "hash_mismatch")
        src.corruptAt = -1
        net.phone.engine.retry(id); net.deliver()
        #expect(net.mac.engine.get(id)?.state == .DONE)
    }

    @Test func insufficientSpace() {
        let net = Net(); net.mac.io.free = 100; net.connect()
        let (id, _) = offer(net, 5000)
        net.deliver()
        #expect(net.phone.engine.get(id)?.error == "insufficient_space")
    }

    @Test func duplicateNames() {
        let net = Net(); net.connect()
        let (a, _) = offer(net, 10, name: "photo.jpg")
        let (b, _) = offer(net, 20, name: "photo.jpg", seed: 3)
        net.deliver()
        #expect(net.mac.engine.get(a)?.savedName == "photo.jpg")
        #expect(net.mac.engine.get(b)?.savedName == "photo (1).jpg")
    }
}

@Suite("Mac file IO")
struct MacFileIOTests {
    @Test func uniqueNamesLikeFinder() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(MacFileIO.uniqueName("photo.jpg", in: dir) == "photo.jpg")
        FileManager.default.createFile(atPath: dir.appendingPathComponent("photo.jpg").path, contents: Data())
        #expect(MacFileIO.uniqueName("photo.jpg", in: dir) == "photo (1).jpg")
        FileManager.default.createFile(atPath: dir.appendingPathComponent("photo (1).jpg").path, contents: Data())
        #expect(MacFileIO.uniqueName("photo.jpg", in: dir) == "photo (2).jpg")
        FileManager.default.createFile(atPath: dir.appendingPathComponent("README").path, contents: Data())
        #expect(MacFileIO.uniqueName("README", in: dir) == "README (1)")
    }

    @Test func realDiskRoundTripWithResume() throws {
        // Real files: write a source, "receive" it into a temp Downloads via a resumed sink, verify bytes.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let io = MacFileIO(downloads: dir.appendingPathComponent("dl"))
        let src = dir.appendingPathComponent("in.bin")
        let content = Data((0..<(3 << 20)).map { UInt8(truncatingIfNeeded: $0 &* 7) })
        try content.write(to: src)
        let offer = FileOffer(transferId: .random(), name: "in.bin", size: Int64(content.count), mime: "x",
                              sha256: Hex.encode(SHA256.hash(data: content)))
        let sink = try io.createSink(offer: offer)
        try sink.write(offset: 0, data: content.prefix(1 << 20)); sink.sync(); sink.close()
        // Restart: reopen at the durable offset and finish.
        let s2 = try #require(io.reopenSink(ref: sink.ref, name: "in.bin", offset: 1 << 20))
        try s2.write(offset: 1 << 20, data: content.suffix(from: 1 << 20))
        #expect(s2.sha256() == offer.sha256)
        let name = try s2.commit()
        #expect(name == "in.bin")
        #expect(try Data(contentsOf: io.downloads.appendingPathComponent(name)) == content)
        let source = try #require(io.openSource(ref: MacFileIO.ref(for: src)!))
        #expect(source.size == Int64(content.count))
        #expect(source.read(offset: 100, max: 10) == content.subdata(in: 100..<110))
        source.close()
    }
}
