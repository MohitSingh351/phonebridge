import Foundation

/// Binary file-chunk frame (PROTOCOL.md §9.2). 36-byte big-endian header followed by the payload.
public struct ChunkFrame: Equatable, Sendable {
    public static let magic: [UInt8] = [0x50, 0x42] // "PB"
    public static let version: UInt8 = 1
    public static let kindFileChunk: UInt8 = 1
    public static let headerSize = 36

    public var transferId: PBUUID
    public var offset: UInt64
    public var payload: Data

    public init(transferId: PBUUID, offset: UInt64, payload: Data) {
        self.transferId = transferId; self.offset = offset; self.payload = payload
    }

    public enum DecodeError: Error, Equatable {
        case tooShort, badMagic, unsupportedVersion(UInt8), unknownKind(UInt8), lengthMismatch, tooLarge
        /// CRC failed. Header fields are still reported so the receiver can `file.nack`.
        case crcMismatch(transferId: PBUUID, offset: UInt64)
    }

    public func encode() -> Data {
        var out = Data(capacity: Self.headerSize + payload.count)
        out.append(contentsOf: Self.magic)
        out.append(Self.version)
        out.append(Self.kindFileChunk)
        out.append(contentsOf: transferId.bytes)
        out.appendBE(offset)
        out.appendBE(UInt32(payload.count))
        out.appendBE(CRC32.checksum(payload))
        out.append(payload)
        return out
    }

    public static func decode(_ data: Data) throws(DecodeError) -> ChunkFrame {
        let b = [UInt8](data)
        guard b.count >= headerSize else { throw .tooShort }
        guard b[0] == magic[0], b[1] == magic[1] else { throw .badMagic }
        guard b[2] == version else { throw .unsupportedVersion(b[2]) }
        guard b[3] == kindFileChunk else { throw .unknownKind(b[3]) }
        let tid = PBUUID(bytes: Array(b[4..<20]))
        let offset = b.readBE64(at: 20)
        let length = Int(b.readBE32(at: 28))
        let crc = b.readBE32(at: 32)
        guard length <= PBConstants.maxChunkPayload else { throw .tooLarge }
        guard b.count == headerSize + length else { throw .lengthMismatch }
        let payload = Data(b[headerSize...])
        guard CRC32.checksum(payload) == crc else { throw .crcMismatch(transferId: tid, offset: offset) }
        return ChunkFrame(transferId: tid, offset: offset, payload: payload)
    }
}

/// CRC-32 (IEEE 802.3 polynomial, reflected), identical to zlib's crc32 and java.util.zip.CRC32.
public enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
        return c
    }

    public static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}

extension Data {
    mutating func appendBE(_ v: UInt64) { Swift.withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) } }
    mutating func appendBE(_ v: UInt32) { Swift.withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) } }
}

extension Array where Element == UInt8 {
    func readBE64(at i: Int) -> UInt64 { self[i..<i + 8].reduce(0) { $0 << 8 | UInt64($1) } }
    func readBE32(at i: Int) -> UInt32 { self[i..<i + 4].reduce(0) { $0 << 8 | UInt32($1) } }
}
