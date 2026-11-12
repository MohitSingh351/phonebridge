import Foundation
import PBProtocol

/// Pairing-token lifecycle (PROTOCOL.md §5.1): single active token, 120 s lifetime, single use,
/// only its SHA-256 is stored, 5 bad attempts invalidate it and block pairing for 60 s.
public final class PairingManager: @unchecked Sendable {
    public static let tokenLifetime: TimeInterval = 120
    public static let maxFailedAttempts = 5
    public static let lockoutDuration: TimeInterval = 60

    public enum Redemption: Equatable, Sendable {
        case ok
        case invalid    // wrong token
        case expired    // used or timed out
        case rateLimited
        case notOpen    // no pairing window
    }

    /// What the QR code needs; the raw token is only ever held here and in the QR image.
    public struct Ticket: Sendable {
        public let token: Data
        public let expiresAt: Date
    }

    private struct Active {
        let hash: Data
        let expiresAt: Date
        var used = false
    }

    private let lock = NSLock()
    private let now: @Sendable () -> Date
    private var active: Active?
    private var failedAttempts = 0
    private var lockedUntil: Date?

    public init(now: @escaping @Sendable () -> Date = { Date() }) { self.now = now }

    /// Open a pairing window with a fresh token. Invalidates any previous token.
    public func open() -> Ticket {
        lock.lock(); defer { lock.unlock() }
        let token = ProtocolCrypto.randomBytes(32)
        let expires = now().addingTimeInterval(Self.tokenLifetime)
        active = Active(hash: ProtocolCrypto.tokenHash(token), expiresAt: expires)
        failedAttempts = 0
        return Ticket(token: token, expiresAt: expires)
    }

    public func close() {
        lock.lock(); active = nil; lock.unlock()
    }

    /// True while an unused, unexpired token exists and pairing isn't locked out. The TLS layer only accepts
    /// unknown client certificates while this is true.
    public var isOpen: Bool {
        lock.lock(); defer { lock.unlock() }
        return stateLocked() == nil
    }

    /// Check a presented token. A valid token is consumed immediately (before any other pairing step).
    public func redeem(_ token: Data) -> Redemption {
        lock.lock(); defer { lock.unlock() }
        if let blocked = stateLocked() { return blocked }
        guard var a = active else { return .notOpen }
        guard ProtocolCrypto.constantTimeEqual(ProtocolCrypto.tokenHash(token), a.hash) else {
            failedAttempts += 1
            if failedAttempts >= Self.maxFailedAttempts {
                active = nil
                lockedUntil = now().addingTimeInterval(Self.lockoutDuration)
            }
            return .invalid
        }
        a.used = true
        active = a
        return .ok
    }

    private func stateLocked() -> Redemption? {
        let t = now()
        if let until = lockedUntil {
            if t < until { return .rateLimited }
            lockedUntil = nil
        }
        guard let a = active else { return .notOpen }
        if a.used || t >= a.expiresAt { return .expired }
        return nil
    }
}
