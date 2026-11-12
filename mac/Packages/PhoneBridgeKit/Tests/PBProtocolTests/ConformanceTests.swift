import Foundation
import Testing
@testable import PBProtocol

/// Loads the shared fixtures in protocol/fixtures (generated from PROTOCOL.md) and proves this implementation
/// decodes and re-encodes them exactly. The Android test suite runs the same fixtures.
enum Fixtures {
    static let root: URL = {
        var u = URL(fileURLWithPath: #filePath)
        for _ in 0..<6 { u.deleteLastPathComponent() } // → repo root
        return u.appendingPathComponent("protocol/fixtures")
    }()

    static func url(_ rel: String) -> URL { root.appendingPathComponent(rel) }
    static func data(_ rel: String) throws -> Data { try Data(contentsOf: url(rel)) }

    /// Valid message fixtures (everything except invalid/, frames/, crypto/).
    static var messageFixtures: [String] {
        let fm = FileManager.default
        let e = fm.enumerator(at: root, includingPropertiesForKeys: nil)!
        return e.compactMap { $0 as? URL }
            .filter { $0.pathExtension == "json" }
            .map { String($0.path.dropFirst(root.path.count + 1)) }
            .filter { !$0.hasPrefix("invalid/") && !$0.hasPrefix("frames/") && !$0.hasPrefix("crypto/") }
            .sorted()
    }

    static func jsonObject(_ d: Data) throws -> NSDictionary {
        try #require(try JSONSerialization.jsonObject(with: d) as? NSDictionary)
    }
}

@Suite("Fixture conformance")
struct ConformanceTests {
    @Test("fixture directory is found and non-empty")
    func fixturesExist() {
        #expect(Fixtures.messageFixtures.count >= 35, "fixtures missing — run protocol/tools/extract_fixtures.py")
    }

    @Test("every message fixture decodes, validates and re-encodes identically",
          arguments: Fixtures.messageFixtures)
    func roundTrip(_ rel: String) throws {
        let raw = try Fixtures.data(rel)
        let env = try EnvelopeCodec.decode(raw)
        let payloadType = try #require(MessageRegistry.byType[env.type], "unknown type \(env.type) in \(rel)")

        // Typed decode → re-encode → must equal the original payload (catches missing/misnamed fields).
        func roundTripTyped<P: MessagePayload>(_ t: P.Type) throws -> Envelope {
            let typed = try env.decode(t)
            return try Envelope.make(typed, seq: env.seq, re: env.re, id: env.id, ts: env.ts)
        }
        let rebuilt = try roundTripTyped(payloadType)

        let expected = try Fixtures.jsonObject(raw).mutableCopy() as! NSMutableDictionary
        let known = Set(["v", "type", "id", "seq", "re", "ts", "payload"])
        for k in expected.allKeys as! [String] where !known.contains(k) { expected.removeObject(forKey: k) }
        let actual = try Fixtures.jsonObject(EnvelopeCodec.encode(rebuilt))
        #expect(actual == expected, "\(rel) did not round-trip")
    }

    @Test("every registered message type has at least one fixture")
    func registryCoverage() throws {
        var seen = Set<String>()
        for rel in Fixtures.messageFixtures {
            seen.insert(try EnvelopeCodec.decode(Fixtures.data(rel)).type)
        }
        let missing = Set(MessageRegistry.byType.keys).subtracting(seen)
        #expect(missing.isEmpty, "no fixture for: \(missing.sorted())")
    }

    @Test("invalid fixtures are rejected with the spec's error code", arguments: [
        ("invalid/missing_type.json", ErrorCode.invalidPayload),
        ("invalid/wrong_version.json", ErrorCode.versionMismatch),
        ("invalid/bad_id.json", ErrorCode.invalidPayload),
    ])
    func invalid(_ rel: String, _ code: ErrorCode) throws {
        let raw = try Fixtures.data(rel)
        #expect(throws: ProtocolError.self) { try EnvelopeCodec.decode(raw) }
        do { _ = try EnvelopeCodec.decode(raw) } catch { #expect(error.code == code) }
    }

    @Test("garbage and oversize input is rejected")
    func garbage() {
        #expect(throws: ProtocolError.self) { try EnvelopeCodec.decode(Data("not json".utf8)) }
        #expect(throws: ProtocolError.self) { try EnvelopeCodec.decode(Data("[]".utf8)) }
        let big = Data(repeating: 0x20, count: PBConstants.maxTextFrameBytes + 1)
        do { _ = try EnvelopeCodec.decode(big) } catch { #expect(error.code == .frameTooLarge) }
    }
}

@Suite("Binary chunk frames")
struct FrameTests {
    struct Expected: Decodable {
        let version: UInt8, kind: UInt8, transferId: String, offset: UInt64, length: Int, crc32: UInt32, payloadHex: String
    }

    @Test(arguments: ["frames/chunk_basic", "frames/chunk_offset"])
    func decodeAndEncode(_ name: String) throws {
        let bin = try Fixtures.data(name + ".bin")
        let exp = try JSONDecoder().decode(Expected.self, from: Fixtures.data(name + ".json"))
        let f = try ChunkFrame.decode(bin)
        #expect(f.transferId.description == exp.transferId)
        #expect(f.offset == exp.offset)
        #expect(f.payload.count == exp.length)
        #expect(Hex.encode(f.payload) == exp.payloadHex)
        #expect(CRC32.checksum(f.payload) == exp.crc32)
        #expect(f.encode() == bin, "re-encoding must be byte-identical")
    }

    @Test("corrupted payload reports crcMismatch with the header's offset")
    func corruption() throws {
        var bin = try Fixtures.data("frames/chunk_offset.bin")
        bin[bin.count - 1] ^= 0xFF
        do {
            _ = try ChunkFrame.decode(bin)
            Issue.record("expected crc failure")
        } catch {
            #expect(error == .crcMismatch(transferId: PBUUID(string: "0f0e0d0c-0b0a-4908-8706-050403020100")!,
                                          offset: 262_144))
        }
    }

    @Test("malformed headers are rejected")
    func malformed() throws {
        let good = try Fixtures.data("frames/chunk_basic.bin")
        #expect(throws: ChunkFrame.DecodeError.tooShort) { try ChunkFrame.decode(good.prefix(10)) }
        var badMagic = good; badMagic[0] = 0
        #expect(throws: ChunkFrame.DecodeError.badMagic) { try ChunkFrame.decode(badMagic) }
        var badVer = good; badVer[2] = 9
        #expect(throws: ChunkFrame.DecodeError.unsupportedVersion(9)) { try ChunkFrame.decode(badVer) }
        #expect(throws: ChunkFrame.DecodeError.lengthMismatch) { try ChunkFrame.decode(good + Data([1])) }
    }
}

@Suite("Crypto test vectors (§14)")
struct CryptoVectorTests {
    struct Vectors: Decodable {
        struct Sas: Decodable { let macSpkiFp, phoneSpkiFp, macNonceHex, code: String }
        struct AuthBytes: Decodable {
            let macNonceHex, clientNonceHex, macDeviceId, phoneDeviceId, bytesHex, sha256: String
        }
        struct TokenHash: Decodable { let tokenB64url, sha256: String }
        let sas: Sas, authSigningBytes: AuthBytes, tokenHash: TokenHash
    }

    let v: Vectors = try! JSONDecoder().decode(Vectors.self, from: Fixtures.data("crypto/vectors.json"))

    @Test func sasCode() {
        let code = ProtocolCrypto.sasCode(macSpkiFingerprint: Hex.decode(v.sas.macSpkiFp)!,
                                          phoneSpkiFingerprint: Hex.decode(v.sas.phoneSpkiFp)!,
                                          macNonce: Hex.decode(v.sas.macNonceHex)!)
        #expect(code == v.sas.code)
    }

    @Test func authSigningBytes() {
        let a = v.authSigningBytes
        let bytes = ProtocolCrypto.authSigningBytes(macNonce: Hex.decode(a.macNonceHex)!,
                                                    clientNonce: Hex.decode(a.clientNonceHex)!,
                                                    macDeviceId: PBUUID(string: a.macDeviceId)!,
                                                    phoneDeviceId: PBUUID(string: a.phoneDeviceId)!)
        #expect(Hex.encode(bytes) == a.bytesHex)
    }

    @Test func tokenHash() {
        let token = B64.decode(v.tokenHash.tokenB64url)!
        #expect(token.count == 32)
        #expect(Hex.encode(ProtocolCrypto.tokenHash(token)) == v.tokenHash.sha256)
    }

    @Test func constantTimeEqual() {
        #expect(ProtocolCrypto.constantTimeEqual(Data([1, 2, 3]), Data([1, 2, 3])))
        #expect(!ProtocolCrypto.constantTimeEqual(Data([1, 2, 3]), Data([1, 2, 4])))
        #expect(!ProtocolCrypto.constantTimeEqual(Data([1, 2]), Data([1, 2, 3])))
    }
}

@Suite("Semantic validation")
struct ValidationTests {
    let phone = PBUUID(string: "a1b2c3d4-e5f6-4789-8abc-def012345678")!

    @Test func sequenceRule() throws {
        var s = InboundSequence()
        try s.accept(1); try s.accept(2)
        #expect(throws: ProtocolError.self) { try s.accept(2) } // replayed
        #expect(throws: ProtocolError.self) { try s.accept(5) } // gap
        var o = OutboundSequence()
        #expect(o.take() == 1); #expect(o.take() == 2)
    }

    @Test func ringDuration() throws {
        #expect(throws: ProtocolError.self) { try Envelope.make(PhoneRing(durationSec: 45), seq: 1).decode(PhoneRing.self) }
        _ = try Envelope.make(PhoneRing(durationSec: 60), seq: 1).decode(PhoneRing.self)
    }

    @Test func clipboardHash() throws {
        var c = ClipboardSet(originDeviceId: phone, text: "hello")
        _ = try Envelope.make(c, seq: 1).decode(ClipboardSet.self)
        c.text = "tampered"
        #expect(throws: ProtocolError.self) { try Envelope.make(c, seq: 1).decode(ClipboardSet.self) }
    }

    @Test(arguments: ["../etc/passwd", "a/b", "..", "", "x\0y"])
    func unsafeFileNames(_ name: String) {
        let o = FileOffer(transferId: .random(), name: name, size: 1, mime: "text/plain", sha256: String(repeating: "a", count: 64))
        #expect(throws: ProtocolError.self) { try Envelope.make(o, seq: 1).decode(FileOffer.self) }
    }

    @Test func base64urlStrict() {
        #expect(B64.decode("AAECAw") == Data([0, 1, 2, 3]))
        #expect(B64.decode("AAECAw==") == nil) // padding not allowed
        #expect(B64.decode("AA+/") == nil)     // standard alphabet not allowed
        #expect(B64.encode(Data([0xfb, 0xff])) == "-_8")
    }

    @Test func wrongTypeDecode() throws {
        let env = try Envelope.make(Ping(), seq: 1)
        #expect(throws: ProtocolError.self) { try env.decode(Pong.self) }
    }

    @Test func uuidsEncodeLowercase() throws {
        let env = try Envelope.make(Ping(), seq: 1, id: PBUUID(UUID(uuidString: "3F8E1C2A-0B4D-4E6F-8A9B-1C2D3E4F5A6B")!))
        let s = String(decoding: try EnvelopeCodec.encode(env), as: UTF8.self)
        #expect(s.contains("3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b"))
    }

    @Test func errorCodes() {
        #expect(ErrorCode.authFailed.closeCode == 4001)
        #expect(ErrorCode.authFailed.isFatal)
        #expect(!ErrorCode.paired.isFatal)
        #expect(!ErrorCode.unsupportedType.isFatal)
    }
}
