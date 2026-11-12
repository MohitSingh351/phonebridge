package dev.phonebridge.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** A message payload model. Semantic checks beyond JSON shape go in [validate]. */
interface MessagePayload {
    /** Throws [ProtocolException] (usually INVALID_PAYLOAD) if the payload breaks spec rules. */
    fun validate() {}
}

/** Binds a wire `type` string to its payload serializer. */
class MessageType<P : MessagePayload>(val type: String, val serializer: KSerializer<P>) {
    override fun toString() = type
}

/** The JSON envelope wrapping every text frame (PROTOCOL.md §3). */
data class Envelope(
    val v: Int = PBConstants.PROTOCOL_VERSION,
    val type: String,
    val id: UUID = UUID.randomUUID(),
    val seq: Long,
    val re: UUID? = null,
    val ts: Long = System.currentTimeMillis(),
    val payload: JsonObject,
) {
    /** Decode the payload into its typed model and validate it. */
    fun <P : MessagePayload> decode(mt: MessageType<P>): P {
        if (type != mt.type) throw ProtocolException.invalid("expected ${mt.type}, got $type")
        val p = try {
            PBJson.decodeFromJsonElement(mt.serializer, payload)
        } catch (e: SerializationException) {
            throw ProtocolException.invalid("$type payload: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ProtocolException.invalid("$type payload: ${e.message}")
        }
        p.validate()
        return p
    }

    companion object {
        fun <P : MessagePayload> make(
            mt: MessageType<P>,
            payload: P,
            seq: Long,
            re: UUID? = null,
            id: UUID = UUID.randomUUID(),
            ts: Long = System.currentTimeMillis(),
        ): Envelope = Envelope(
            type = mt.type, id = id, seq = seq, re = re, ts = ts,
            payload = PBJson.encodeToJsonElement(mt.serializer, payload).jsonObject,
        )
    }
}

/** Shared JSON configuration: ignore unknown fields, omit nulls, always write defaults. */
val PBJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Encode/decode envelopes to and from WebSocket text frames. */
object EnvelopeCodec {
    /** Decodes and checks shape + version. Payload semantics are checked by [Envelope.decode]. */
    fun decode(text: String): Envelope {
        if (text.toByteArray(Charsets.UTF_8).size > PBConstants.MAX_TEXT_FRAME_BYTES) {
            throw ProtocolException(ErrorCode.FRAME_TOO_LARGE, "text frame too large")
        }
        val obj = try {
            PBJson.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw ProtocolException.invalid("malformed JSON")

        fun prim(key: String): JsonPrimitive =
            (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                ?: throw ProtocolException.invalid("missing or invalid field '$key'")
        fun str(key: String) = prim(key).takeIf { it.isString }?.content
            ?: throw ProtocolException.invalid("field '$key' must be a string")
        fun long(key: String) = prim(key).takeIf { !it.isString }?.longOrNull
            ?: throw ProtocolException.invalid("field '$key' must be an integer")

        val v = prim("v").takeIf { !it.isString }?.intOrNull ?: throw ProtocolException.invalid("missing or invalid field 'v'")
        val type = str("type").ifEmpty { throw ProtocolException.invalid("empty type") }
        val id = Uuids.parse(str("id")) ?: throw ProtocolException.invalid("invalid id")
        val seq = long("seq").also { if (it < 1) throw ProtocolException.invalid("seq must be >= 1") }
        val ts = long("ts")
        val re = when (val r = obj["re"]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (r.isString) Uuids.parse(r.content) ?: throw ProtocolException.invalid("invalid re")
                                else throw ProtocolException.invalid("invalid re")
            else -> throw ProtocolException.invalid("invalid re")
        }
        val payload = obj["payload"] as? JsonObject ?: throw ProtocolException.invalid("payload must be an object")
        if (v != PBConstants.PROTOCOL_VERSION) {
            throw ProtocolException(ErrorCode.VERSION_MISMATCH, "unsupported protocol version $v")
        }
        return Envelope(v, type, id, seq, re, ts, payload)
    }

    fun encode(e: Envelope): String = buildJsonObject {
        put("v", e.v)
        put("type", e.type)
        put("id", e.id.toString())
        put("seq", e.seq)
        e.re?.let { put("re", it.toString()) }
        put("ts", e.ts)
        put("payload", e.payload as JsonElement)
    }.toString()
}

/** Enforces the per-connection sequence rule (PROTOCOL.md §3): inbound `seq` must be exactly last + 1. */
class InboundSequence {
    var last: Long = 0
        private set

    fun accept(seq: Long) {
        if (seq != last + 1) {
            throw ProtocolException(ErrorCode.SEQUENCE_VIOLATION, "expected seq ${last + 1}, got $seq")
        }
        last = seq
    }
}

/** Allocates outbound `seq` values starting at 1. */
class OutboundSequence {
    private var next = 1L
    @Synchronized fun take(): Long = next++
}
