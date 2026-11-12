package dev.phonebridge.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64
import java.util.UUID

/** Protocol constants (PROTOCOL.md §2, §13). Must match mac `PBConstants`. */
object PBConstants {
    const val PROTOCOL_VERSION = 1
    const val MIN_PROTOCOL_VERSION = 1
    const val MAX_TEXT_FRAME_BYTES = 2 * 1024 * 1024
    const val MAX_CHUNK_PAYLOAD = 262_144
    const val DEFAULT_HEARTBEAT_SEC = 15
    const val SERVICE_TYPE = "_phonebridge._tcp"
    const val WEBSOCKET_PATH = "/v1"
}

/** Strict UUID handling: java.util.UUID.fromString accepts non-canonical forms, so validate first. */
object Uuids {
    private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun parse(s: String): UUID? = if (CANONICAL.matches(s)) UUID.fromString(s) else null

    fun toBytes(u: UUID): ByteArray {
        val out = ByteArray(16)
        var msb = u.mostSignificantBits
        var lsb = u.leastSignificantBits
        for (i in 7 downTo 0) { out[i] = (msb and 0xFF).toByte(); msb = msb ushr 8 }
        for (i in 15 downTo 8) { out[i] = (lsb and 0xFF).toByte(); lsb = lsb ushr 8 }
        return out
    }

    fun fromBytes(b: ByteArray): UUID {
        require(b.size == 16)
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (b[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (b[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}

/** Serializes UUIDs as canonical lowercase strings (PROTOCOL.md §1). */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("pb.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID {
        val s = decoder.decodeString()
        return Uuids.parse(s) ?: throw SerializationException("invalid uuid '$s'")
    }
}

typealias PBUUID = @Serializable(with = UuidSerializer::class) UUID

/** Binary data as base64url without padding (PROTOCOL.md §1). Content-based equality. */
@Serializable(with = B64Serializer::class)
class B64(val bytes: ByteArray) {
    val string: String get() = encode(bytes)
    override fun equals(other: Any?) = other is B64 && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "B64($string)"

    companion object {
        private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")
        fun encode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        /** Strict: rejects padding and the standard (+/) alphabet. */
        fun decode(s: String): ByteArray? {
            if (!ALPHABET.matches(s) || s.length % 4 == 1) return null
            return try { Base64.getUrlDecoder().decode(s) } catch (_: IllegalArgumentException) { null }
        }
    }
}

object B64Serializer : KSerializer<B64> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("pb.B64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: B64) = encoder.encodeString(value.string)
    override fun deserialize(decoder: Decoder): B64 {
        val s = decoder.decodeString()
        return B64(B64.decode(s) ?: throw SerializationException("invalid base64url"))
    }
}

object Hex {
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    fun encode(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun isSha256(s: String) = SHA256.matches(s)
}

/** Enums whose wire value is a string; unknown values decode to a fallback (forward compatibility). */
interface WireEnum { val wire: String }

open class LenientEnumSerializer<E>(
    name: String,
    private val values: List<E>,
    private val unknown: E,
) : KSerializer<E> where E : Enum<E>, E : WireEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("pb.$name", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): E {
        val s = decoder.decodeString()
        return values.firstOrNull { it.wire == s } ?: unknown
    }
}
