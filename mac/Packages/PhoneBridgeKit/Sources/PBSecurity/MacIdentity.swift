import Foundation
import PBProtocol
import Security
import SwiftASN1
import X509

/// The Mac's long-lived TLS identity: a P-256 key and self-signed certificate stored in the Keychain.
/// The phone pins `fingerprint` (from the pairing QR code), so the certificate is never CA-validated.
public struct MacIdentity: @unchecked Sendable {
    public let identity: SecIdentity
    public let certificate: SecCertificate
    public let fingerprint: Data

    public var fingerprintHex: String { Hex.encode(fingerprint) }
}

public enum MacIdentityStore {
    public static let defaultLabel = "dev.phonebridge.mac-identity"

    public enum Error: Swift.Error, CustomStringConvertible {
        case keychain(String, OSStatus)
        case keyGeneration(String)

        public var description: String {
            switch self {
            case .keychain(let what, let status):
                "\(what): \(SecCopyErrorMessageString(status, nil) as String? ?? "OSStatus \(status)")"
            case .keyGeneration(let m): "key generation failed: \(m)"
            }
        }
    }

    /// Load the identity from the Keychain, creating it on first launch.
    public static func loadOrCreate(label: String = defaultLabel, commonName: String) throws -> MacIdentity {
        if let existing = try load(label: label) { return existing }
        return try create(label: label, commonName: commonName)
    }

    public static func load(label: String = defaultLabel) throws -> MacIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: label,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let ref = out, CFGetTypeID(ref) == SecIdentityGetTypeID() else {
            throw Error.keychain("load identity", status)
        }
        let identity = ref as! SecIdentity
        var cert: SecCertificate?
        let s2 = SecIdentityCopyCertificate(identity, &cert)
        guard s2 == errSecSuccess, let cert else { throw Error.keychain("identity certificate", s2) }
        return MacIdentity(identity: identity, certificate: cert, fingerprint: try SPKI.fingerprint(certificate: cert))
    }

    /// Delete the identity (used when the user resets PhoneBridge; all phones must re-pair).
    public static func delete(label: String = defaultLabel) {
        for cls in [kSecClassCertificate, kSecClassKey] {
            SecItemDelete([kSecClass as String: cls, kSecAttrLabel as String: label] as CFDictionary)
        }
    }

    static func create(label: String, commonName: String) throws -> MacIdentity {
        // 1. Private key generated inside the Keychain (never leaves it).
        let keyAttrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrLabel as String: label,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrLabel as String: label,
            ],
        ]
        var err: Unmanaged<CFError>?
        guard let secKey = SecKeyCreateRandomKey(keyAttrs as CFDictionary, &err) else {
            throw Error.keyGeneration(err.map { "\($0.takeRetainedValue())" } ?? "unknown")
        }

        // 2. Self-signed certificate signed by that key.
        let key = try Certificate.PrivateKey(secKey)
        let name = try DistinguishedName { CommonName(commonName) }
        let now = Date()
        let cert = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: key.publicKey,
            notValidBefore: now.addingTimeInterval(-86_400),
            notValidAfter: now.addingTimeInterval(20 * 365 * 86_400),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
                Critical(KeyUsage(digitalSignature: true))
                try ExtendedKeyUsage([.serverAuth, .clientAuth])
            },
            issuerPrivateKey: key
        )
        var ser = DER.Serializer()
        try ser.serialize(cert)
        guard let secCert = SecCertificateCreateWithData(nil, Data(ser.serializedBytes) as CFData) else {
            throw Error.keyGeneration("could not parse generated certificate")
        }

        // 3. Store the certificate next to the key; the Keychain pairs them into an identity.
        let add: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: secCert,
            kSecAttrLabel as String: label,
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw Error.keychain("store certificate", status)
        }
        var identity: SecIdentity?
        let s2 = SecIdentityCreateWithCertificate(nil, secCert, &identity)
        guard s2 == errSecSuccess, let identity else { throw Error.keychain("create identity", s2) }
        return MacIdentity(identity: identity, certificate: secCert, fingerprint: try SPKI.fingerprint(certificate: secCert))
    }
}
