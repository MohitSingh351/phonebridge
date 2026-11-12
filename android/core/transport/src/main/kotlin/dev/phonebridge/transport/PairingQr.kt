package dev.phonebridge.transport

import dev.phonebridge.protocol.B64
import dev.phonebridge.protocol.Hex
import dev.phonebridge.protocol.Uuids
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * Parsed pairing QR code (PROTOCOL.md §5.1):
 * `phonebridge://pair?v=1&h=<ip>&p=<port>&fp=<sha256 hex>&t=<token>&id=<macId>&n=<name>`
 */
data class PairingQr(
    val host: String,
    val port: Int,
    val macFingerprintHex: String,
    val token: ByteArray,
    val macId: UUID,
    val macName: String,
) {
    val macFingerprint: ByteArray get() = Hex.decode(macFingerprintHex)

    override fun equals(other: Any?) = other is PairingQr && host == other.host && port == other.port &&
        macFingerprintHex == other.macFingerprintHex && token.contentEquals(other.token) &&
        macId == other.macId && macName == other.macName
    override fun hashCode() = macId.hashCode()
    override fun toString() = "PairingQr(host=$host, port=$port, macId=$macId, name=$macName)" // never log the token

    companion object {
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

        /** Returns null for anything that isn't a valid PhoneBridge v1 pairing code. */
        fun parse(text: String): PairingQr? {
            val uri = try { URI(text.trim()) } catch (_: Exception) { return null }
            if (uri.scheme != "phonebridge" || uri.host != "pair") return null
            val q = (uri.rawQuery ?: return null).split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            if (q["v"] != "1") return null
            val host = q["h"]?.takeIf { IPV4.matches(it) } ?: return null
            val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fp = q["fp"]?.takeIf(Hex::isSha256) ?: return null
            val token = q["t"]?.let(B64::decode)?.takeIf { it.size == 32 } ?: return null
            val id = q["id"]?.let(Uuids::parse) ?: return null
            val name = q["n"]?.takeIf { it.isNotBlank() }?.take(64) ?: "Mac"
            return PairingQr(host, port, fp, token, id, name)
        }
    }
}
