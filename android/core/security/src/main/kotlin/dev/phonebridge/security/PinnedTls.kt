package dev.phonebridge.security

import dev.phonebridge.protocol.ProtocolCrypto
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trusts exactly one server key: the Mac's, whose SPKI SHA-256 came from the pairing QR code.
 * No CA validation, no hostname checks (PROTOCOL.md §2) — the pin is the whole trust decision.
 */
class PinnedTrustManager(private val expectedFingerprint: ByteArray) : X509ExtendedTrustManager() {
    init { require(expectedFingerprint.size == 32) }

    fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        val fp = ProtocolCrypto.spkiFingerprint(leaf.publicKey.encoded)
        if (!ProtocolCrypto.constantTimeEqual(fp, expectedFingerprint)) {
            throw CertificateException("server key does not match the paired Mac")
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)

    // We are never a TLS server.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client mode only")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("client mode only")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("client mode only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Presents the Keystore-held device certificate as the TLS client certificate (mutual TLS). */
class DeviceKeyManager(private val identity: DeviceIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = identity.alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = identity.alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(identity.alias)
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
}

/** TLS 1.3 client context: Keystore client cert + pinned Mac key. */
class PinnedTls(identity: DeviceIdentity, macFingerprint: ByteArray) {
    val trustManager = PinnedTrustManager(macFingerprint)
    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLSv1.3").run {
        init(arrayOf(DeviceKeyManager(identity)), arrayOf(trustManager), null)
        socketFactory
    }
}
