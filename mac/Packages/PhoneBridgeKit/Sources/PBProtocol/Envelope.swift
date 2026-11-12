import Foundation

public enum PBConstants {
    /// Protocol major version (PROTOCOL.md §13).
    public static let protocolVersion = 1
    public static let minProtocolVersion = 1
    public static let maxTextFrameBytes = 2 * 1024 * 1024
    public static let maxChunkPayload = 262_144
    public static let defaultHeartbeatSec = 15
    public static let bonjourType = "_phonebridge._tcp"
    public static let webSocketPath = "/v1"
}

/// A message payload. Each payload type declares its wire `type` string and validates its own semantics.
public protocol MessagePayload: Codable, Sendable, Equatable {
    static var type: String { get }
    /// Semantic checks beyond JSON shape (ranges, lengths, hashes). Throws `ProtocolError(.invalidPayload)`.
    func validate() throws
}

public extension MessagePayload {
    func validate() throws {}
}

/// The JSON envelope wrapping every text frame (PROTOCOL.md §3).
public struct Envelope: Equatable, Sendable {
    public var v: Int
    public var type: String
    public var id: PBUUID
    public var seq: UInt64
    public var re: PBUUID?
    public var ts: Int64
    public var payload: JSONValue

    public init(v: Int = PBConstants.protocolVersion, type: String, id: PBUUID = .random(), seq: UInt64,
                re: PBUUID? = nil, ts: Int64 = Envelope.now(), payload: JSONValue) {
        self.v = v; self.type = type; self.id = id; self.seq = seq; self.re = re; self.ts = ts; self.payload = payload
    }

    public static func now() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    /// Build an envelope from a typed payload.
    public static func make<P: MessagePayload>(_ payload: P, seq: UInt64, re: PBUUID? = nil,
                                               id: PBUUID = .random(), ts: Int64 = Envelope.now()) throws -> Envelope {
        let data = try PBJSON.encoder.encode(payload)
        let value = try PBJSON.decoder.decode(JSONValue.self, from: data)
        return Envelope(type: P.type, id: id, seq: seq, re: re, ts: ts, payload: value)
    }

    /// Decode the payload into its typed model and validate it.
    public func decode<P: MessagePayload>(_: P.Type) throws -> P {
        guard type == P.type else {
            throw ProtocolError.invalid("expected \(P.type), got \(type)")
        }
        let p: P
        do {
            let data = try PBJSON.encoder.encode(payload)
            p = try PBJSON.decoder.decode(P.self, from: data)
        } catch {
            throw ProtocolError.invalid("\(type) payload: \(error)")
        }
        try p.validate()
        return p
    }
}

extension Envelope: Codable {
    enum CodingKeys: String, CodingKey { case v, type, id, seq, re, ts, payload }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func req<T: Decodable>(_ t: T.Type, _ k: CodingKeys) throws -> T {
            guard let value = try? c.decodeIfPresent(t, forKey: k) else {
                throw ProtocolError.invalid("missing or invalid field '\(k.rawValue)'")
            }
            return value
        }
        let id = try req(String.self, .id)
        guard let pid = PBUUID(string: id) else { throw ProtocolError.invalid("invalid id") }
        let payload = try req(JSONValue.self, .payload)
        guard payload.isObject else { throw ProtocolError.invalid("payload must be an object") }
        let seq = try req(UInt64.self, .seq)
        guard seq >= 1 else { throw ProtocolError.invalid("seq must be >= 1") }
        var re: PBUUID?
        if c.contains(.re), !((try? c.decodeNil(forKey: .re)) ?? false) {
            guard let s = try? c.decode(String.self, forKey: .re), let r = PBUUID(string: s) else {
                throw ProtocolError.invalid("invalid re")
            }
            re = r
        }
        self.init(v: try req(Int.self, .v), type: try req(String.self, .type), id: pid, seq: seq, re: re,
                  ts: try req(Int64.self, .ts), payload: payload)
        guard !type.isEmpty else { throw ProtocolError.invalid("empty type") }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(v, forKey: .v)
        try c.encode(type, forKey: .type)
        try c.encode(id, forKey: .id)
        try c.encode(seq, forKey: .seq)
        try c.encodeIfPresent(re, forKey: .re)
        try c.encode(ts, forKey: .ts)
        try c.encode(payload, forKey: .payload)
    }
}

/// Encode/decode envelopes to and from WebSocket text frames.
public enum EnvelopeCodec {
    /// Decodes and checks shape + version. Payload semantics are checked by `Envelope.decode(_:)`.
    public static func decode(_ data: Data) throws(ProtocolError) -> Envelope {
        guard data.count <= PBConstants.maxTextFrameBytes else {
            throw ProtocolError(.frameTooLarge, "text frame \(data.count) bytes")
        }
        let env: Envelope
        do {
            env = try PBJSON.decoder.decode(Envelope.self, from: data)
        } catch let e as ProtocolError {
            throw e
        } catch {
            throw ProtocolError.invalid("malformed JSON: \(error.localizedDescription)")
        }
        guard env.v == PBConstants.protocolVersion else {
            throw ProtocolError(.versionMismatch, "unsupported protocol version \(env.v)")
        }
        return env
    }

    public static func encode(_ envelope: Envelope) throws -> Data {
        try PBJSON.encoder.encode(envelope)
    }
}

enum PBJSON {
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.withoutEscapingSlashes, .sortedKeys]
        return e
    }()
    static let decoder = JSONDecoder()
}
