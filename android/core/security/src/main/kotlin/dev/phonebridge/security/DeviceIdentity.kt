package dev.phonebridge.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.os.Build
import dev.phonebridge.protocol.ProtocolCrypto
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The phone's identity for one paired Mac: a P-256 key in the Android Keystore (non-exportable, StrongBox
 * when available) plus the self-signed certificate the Keystore generates for it. Used as the TLS client
 * certificate and to sign `auth` (PROTOCOL.md §4.2).
 */
class DeviceIdentity private constructor(val alias: String, private val entry: KeyStore.PrivateKeyEntry) {
    val privateKey: PrivateKey get() = entry.privateKey
    val certificate: X509Certificate get() = entry.certificate as X509Certificate

    /** DER SubjectPublicKeyInfo — `PublicKey.encoded` is SPKI for EC keys. */
    val spkiDer: ByteArray get() = certificate.publicKey.encoded
    val fingerprint: ByteArray get() = ProtocolCrypto.spkiFingerprint(spkiDer)

    /** ECDSA P-256 / SHA-256, DER-encoded (what the Mac verifies). */
    fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(message)
        sign()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

        fun aliasFor(macId: String) = "pb.device.$macId"

        fun load(alias: String): DeviceIdentity? {
            val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
            return DeviceIdentity(alias, entry)
        }

        /** Creates a fresh key (replacing any old one under [alias]). Prefers StrongBox hardware. */
        fun create(alias: String): DeviceIdentity {
            delete(alias)
            try {
                generate(alias, strongBox = Build.VERSION.SDK_INT >= 28)
            } catch (_: StrongBoxUnavailableException) {
                generate(alias, strongBox = false)
            }
            return requireNotNull(load(alias)) { "key generation succeeded but entry missing" }
        }

        fun delete(alias: String) {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }

        private fun generate(alias: String, strongBox: Boolean) {
            val now = Date()
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=PhoneBridge Phone"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now.time - 86_400_000L))
                .setCertificateNotAfter(Date(now.time + 20L * 365 * 86_400_000L))
                .setUserAuthenticationRequired(false) // must work in the background
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
