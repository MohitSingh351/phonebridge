package dev.phonebridge.protocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Spec-defined byte layouts used by the security layer (PROTOCOL.md §4.2, §5.1, §5.3).
 * Pure functions — the actual keys live in core:security (Android Keystore).
 */
object ProtocolCrypto {
    private val random = SecureRandom()

    /** Bytes the phone signs in `auth` (§4.2). */
    fun authSigningBytes(macNonce: ByteArray, clientNonce: ByteArray, macDeviceId: UUID, phoneDeviceId: UUID): ByteArray =
        ByteArrayOutputStream().apply {
            write("pb-auth-v1".toByteArray(Charsets.UTF_8))
            write(0)
            write(macNonce)
            write(clientNonce)
            write(macDeviceId.toString().toByteArray(Charsets.UTF_8))
            write(0)
            write(phoneDeviceId.toString().toByteArray(Charsets.UTF_8))
        }.toByteArray()

    /** Six-digit confirmation code shown on both screens during pairing (§5.3). */
    fun sasCode(macSpkiFingerprint: ByteArray, phoneSpkiFingerprint: ByteArray, macNonce: ByteArray): String {
        val h = sha256("pb-sas-v1".toByteArray(Charsets.UTF_8) + macSpkiFingerprint + phoneSpkiFingerprint + macNonce)
        val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return "%06d".format(n % 1_000_000)
    }

    fun tokenHash(token: ByteArray): ByteArray = sha256(token)

    /** SHA-256 over DER SubjectPublicKeyInfo (= `PublicKey.encoded` for EC keys) → fingerprint (§1). */
    fun spkiFingerprint(spkiDer: ByteArray): ByteArray = sha256(spkiDer)

    /** Constant-time comparison (MessageDigest.isEqual is constant-time since JDK 6u17). */
    fun constantTimeEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
