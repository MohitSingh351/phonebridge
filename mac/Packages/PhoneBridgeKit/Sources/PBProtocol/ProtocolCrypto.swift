import CryptoKit
import Foundation

/// Spec-defined byte layouts used by the security layer (PROTOCOL.md §4.2, §5.1, §5.3).
/// Pure functions — the actual keys live in PBSecurity (Keychain).
public enum ProtocolCrypto {
    /// Bytes the phone signs in `auth` (§4.2).
    public static func authSigningBytes(macNonce: Data, clientNonce: Data, macDeviceId: PBUUID,
                                        phoneDeviceId: PBUUID) -> Data {
        var d = Data("pb-auth-v1".utf8)
        d.append(0)
        d.append(macNonce)
        d.append(clientNonce)
        d.append(contentsOf: Data(macDeviceId.description.utf8))
        d.append(0)
        d.append(contentsOf: Data(phoneDeviceId.description.utf8))
        return d
    }

    /// Six-digit confirmation code shown on both screens during pairing (§5.3).
    public static func sasCode(macSpkiFingerprint: Data, phoneSpkiFingerprint: Data, macNonce: Data) -> String {
        var d = Data("pb-sas-v1".utf8)
        d.append(macSpkiFingerprint)
        d.append(phoneSpkiFingerprint)
        d.append(macNonce)
        let h = Array(SHA256.hash(data: d))
        let n = (UInt32(h[0]) << 24) | (UInt32(h[1]) << 16) | (UInt32(h[2]) << 8) | UInt32(h[3])
        return String(format: "%06u", n % 1_000_000)
    }

    /// The Mac stores only this hash of the pairing token (§5.1).
    public static func tokenHash(_ token: Data) -> Data { Data(SHA256.hash(data: token)) }

    /// Constant-time comparison for secrets.
    public static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }

    /// SHA-256 of a DER SubjectPublicKeyInfo → fingerprint (§1).
    public static func spkiFingerprint(spkiDER: Data) -> Data { Data(SHA256.hash(data: spkiDER)) }

    public static func randomBytes(_ n: Int) -> Data {
        var g = SystemRandomNumberGenerator()
        return Data((0..<n).map { _ in UInt8.random(in: .min ... .max, using: &g) })
    }
}
