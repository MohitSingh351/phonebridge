import Foundation

/// UUID that always encodes lowercase (PROTOCOL.md §1). Foundation's UUID encodes uppercase.
public struct PBUUID: Hashable, Sendable, Codable, CustomStringConvertible {
    public let value: UUID

    public init(_ value: UUID = UUID()) { self.value = value }

    public init?(string: String) {
        guard let u = UUID(uuidString: string) else { return nil }
        value = u
    }

    public static func random() -> PBUUID { PBUUID(UUID()) }

    public var description: String { value.uuidString.lowercased() }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let u = UUID(uuidString: s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "invalid uuid \(s)"))
        }
        value = u
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(description)
    }

    /// Raw 16 bytes in RFC 4122 order (used by binary chunk frames).
    public var bytes: [UInt8] {
        withUnsafeBytes(of: value.uuid) { Array($0) }
    }

    public init(bytes: [UInt8]) {
        precondition(bytes.count == 16)
        let t = (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                 bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])
        value = UUID(uuid: t)
    }
}

/// Binary data encoded as base64url without padding (PROTOCOL.md §1).
public struct B64: Hashable, Sendable, Codable {
    public let data: Data

    public init(_ data: Data) { self.data = data }

    public init?(string: String) {
        guard let d = B64.decode(string) else { return nil }
        data = d
    }

    public var string: String { B64.encode(data) }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let d = B64.decode(s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "invalid base64url"))
        }
        data = d
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(string)
    }

    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Strict: rejects padding and characters outside the base64url alphabet.
    public static func decode(_ s: String) -> Data? {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        guard s.allSatisfy(allowed.contains), s.count % 4 != 1 else { return nil }
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b += String(repeating: "=", count: (4 - b.count % 4) % 4)
        return Data(base64Encoded: b)
    }
}

/// Lowercase hex helpers.
public enum Hex {
    public static func encode<D: Sequence>(_ bytes: D) -> String where D.Element == UInt8 {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    public static func decode(_ s: String) -> Data? {
        guard s.count % 2 == 0 else { return nil }
        var out = Data(capacity: s.count / 2)
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            guard let byte = UInt8(s[i..<j], radix: 16) else { return nil }
            out.append(byte)
            i = j
        }
        return out
    }

    public static func isSHA256(_ s: String) -> Bool {
        s.count == 64 && s.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }
}

/// String enums that decode unknown values to a fallback instead of failing (forward compatibility).
public protocol LenientStringEnum: RawRepresentable, Codable, Sendable, Hashable where RawValue == String {
    static var unknownValue: Self { get }
}

public extension LenientStringEnum {
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = Self(rawValue: raw) ?? Self.unknownValue
    }
}
