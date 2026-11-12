/// Enforces the per-connection sequence rule (PROTOCOL.md §3): inbound `seq` must be exactly last + 1.
public struct InboundSequence: Sendable {
    public private(set) var last: UInt64 = 0
    public init() {}

    public mutating func accept(_ seq: UInt64) throws(ProtocolError) {
        guard seq == last &+ 1 else {
            throw ProtocolError(.sequenceViolation, "expected seq \(last &+ 1), got \(seq)")
        }
        last = seq
    }
}

/// Allocates outbound `seq` values starting at 1.
public struct OutboundSequence: Sendable {
    private var next: UInt64 = 1
    public init() {}
    public mutating func take() -> UInt64 {
        defer { next &+= 1 }
        return next
    }
}
