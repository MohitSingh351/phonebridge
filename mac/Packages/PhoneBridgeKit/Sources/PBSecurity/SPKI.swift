import CryptoKit
import Foundation
import PBProtocol
import Security

/// P-256 SubjectPublicKeyInfo helpers. Both platforms use P-256 only, so the SPKI DER is a fixed 26-byte
/// header followed by the 65-byte uncompressed point (identical to Android's `PublicKey.encoded`).
public enum SPKI {
    static let p256Prefix: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ]

    public enum Error: Swift.Error { case notP256, noPublicKey }

    /// SPKI DER from a raw X9.63 uncompressed point (0x04 ‖ X ‖ Y).
    public static func der(x963 point: Data) throws -> Data {
        guard point.count == 65, point.first == 0x04 else { throw Error.notP256 }
        return Data(p256Prefix) + point
    }

    public static func der(publicKey: SecKey) throws -> Data {
        var err: Unmanaged<CFError>?
        guard let raw = SecKeyCopyExternalRepresentation(publicKey, &err) as Data? else { throw Error.noPublicKey }
        return try der(x963: raw)
    }

    public static func der(certificate: SecCertificate) throws -> Data {
        guard let key = SecCertificateCopyKey(certificate) else { throw Error.noPublicKey }
        return try der(publicKey: key)
    }

    /// SHA-256 fingerprint of a certificate's SPKI (PROTOCOL.md §1).
    public static func fingerprint(certificate: SecCertificate) throws -> Data {
        ProtocolCrypto.spkiFingerprint(spkiDER: try der(certificate: certificate))
    }

    /// Verify an `auth` signature (ECDSA P-256 / SHA-256, DER) against a certificate's key.
    public static func verify(signatureDER: Data, message: Data, spkiDER: Data) -> Bool {
        guard let key = try? P256.Signing.PublicKey(derRepresentation: spkiDER),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: signatureDER) else { return false }
        return key.isValidSignature(sig, for: message)
    }
}
