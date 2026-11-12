package dev.phonebridge.security

import dev.phonebridge.protocol.ProtocolCrypto
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

class PinnedTrustManagerTest {
    private fun ecKey(): PublicKey = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair().public

    /** Minimal X509Certificate stub: the trust manager only looks at the public key. */
    private fun cert(key: PublicKey): X509Certificate = object : X509Certificate() {
        override fun getPublicKey() = key
        override fun getEncoded() = ByteArray(0)
        override fun verify(key: PublicKey?) {}
        override fun verify(key: PublicKey?, sigProvider: String?) {}
        override fun toString() = "stub"
        override fun checkValidity() {}
        override fun checkValidity(date: Date?) {}
        override fun getVersion() = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN() = X500Principal("CN=x")
        override fun getSubjectDN() = X500Principal("CN=x")
        override fun getNotBefore() = Date(0)
        override fun getNotAfter() = Date(Long.MAX_VALUE)
        override fun getTBSCertificate() = ByteArray(0)
        override fun getSignature() = ByteArray(0)
        override fun getSigAlgName() = "SHA256withECDSA"
        override fun getSigAlgOID() = "1.2.840.10045.4.3.2"
        override fun getSigAlgParams() = null
        override fun getIssuerUniqueID() = null
        override fun getSubjectUniqueID() = null
        override fun getKeyUsage() = null
        override fun getBasicConstraints() = -1
        override fun hasUnsupportedCriticalExtension() = false
        override fun getCriticalExtensionOIDs() = null
        override fun getNonCriticalExtensionOIDs() = null
        override fun getExtensionValue(oid: String?) = null
    }

    @Test
    fun `accepts only the pinned Mac key`() {
        val mac = ecKey()
        val tm = PinnedTrustManager(ProtocolCrypto.spkiFingerprint(mac.encoded))
        tm.checkServerTrusted(arrayOf(cert(mac)), "ECDHE_ECDSA")
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(cert(ecKey())), "ECDHE_ECDSA") }
    }

    @Test
    fun `rejects empty chains and client mode`() {
        val tm = PinnedTrustManager(ByteArray(32))
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(), "x") }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(null, "x") }
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(arrayOf(cert(ecKey())), "x") }
    }

    @Test
    fun `java SPKI encoding matches the protocol layout`() {
        // Same 26-byte P-256 SPKI header the Mac uses (mac PBSecurity/SPKI.swift).
        val spki = ecKey().encoded
        assert(spki.size == 91)
        assert(spki.copyOf(26).joinToString("") { "%02x".format(it) } ==
            "3059301306072a8648ce3d020106082a8648ce3d030107034200")
    }
}
