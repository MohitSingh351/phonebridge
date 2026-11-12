package dev.phonebridge.protocol

import kotlinx.serialization.Serializable
import java.security.MessageDigest

// Typed payloads for every message in PROTOCOL.md. Field names match the spec exactly.
// Mirrors mac PBProtocol/Messages.swift — keep the two in sync via the shared fixtures.

// ---------------------------------------------------------------- Session (§4)

@Serializable(with = CapabilityStateSerializer::class)
enum class CapabilityState(override val wire: String) : WireEnum {
    AVAILABLE("available"), PERMISSION_MISSING("permission_missing"), DISABLED("disabled"), UNKNOWN("unknown")
}
object CapabilityStateSerializer :
    LenientEnumSerializer<CapabilityState>("CapabilityState", CapabilityState.entries, CapabilityState.UNKNOWN)

@Serializable
data class Capability(val id: String, val v: Int, val state: CapabilityState)

@Serializable(with = HelloModeSerializer::class)
enum class HelloMode(override val wire: String) : WireEnum { SESSION("session"), PAIRING("pairing"), UNKNOWN("unknown") }
object HelloModeSerializer : LenientEnumSerializer<HelloMode>("HelloMode", HelloMode.entries, HelloMode.UNKNOWN)

@Serializable
data class Hello(
    val mode: HelloMode,
    val protocolVersion: Int = PBConstants.PROTOCOL_VERSION,
    val minProtocolVersion: Int = PBConstants.MIN_PROTOCOL_VERSION,
    val deviceId: PBUUID,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val nonce: B64,
    val capabilities: List<Capability>,
) : MessagePayload {
    override fun validate() {
        if (nonce.bytes.size != 32) throw ProtocolException.invalid("nonce must be 32 bytes")
        if (mode == HelloMode.UNKNOWN) throw ProtocolException.invalid("unknown hello mode")
    }
}

@Serializable
data class Auth(
    val deviceId: PBUUID,
    val protocolVersion: Int = PBConstants.PROTOCOL_VERSION,
    val appVersion: String,
    val clientNonce: B64,
    val sig: B64,
    val capabilities: List<Capability>,
) : MessagePayload {
    override fun validate() {
        if (clientNonce.bytes.size != 32) throw ProtocolException.invalid("clientNonce must be 32 bytes")
        if (sig.bytes.isEmpty() || sig.bytes.size > 80) throw ProtocolException.invalid("bad signature length")
    }
}

@Serializable
data class Ready(
    val sessionId: PBUUID,
    val enabled: List<String>,
    val heartbeatSec: Int = PBConstants.DEFAULT_HEARTBEAT_SEC,
) : MessagePayload {
    override fun validate() {
        if (heartbeatSec !in 5..300) throw ProtocolException.invalid("heartbeatSec out of range")
    }
}

@Serializable data object Ping : MessagePayload
@Serializable data object Pong : MessagePayload

@Serializable data class CapabilitiesChanged(val capabilities: List<Capability>) : MessagePayload
@Serializable data class CapabilitiesEnabled(val enabled: List<String>) : MessagePayload

@Serializable
data class ErrorPayload(val code: ErrorCode, val message: String? = null, val fatal: Boolean = code.isFatal) :
    MessagePayload

// ---------------------------------------------------------------- Pairing (§5)

@Serializable
data class PairRequest(
    val token: B64,
    val deviceId: PBUUID,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val platform: String = "android",
    val osVersion: String,
    val sdkInt: Int,
    val appVersion: String,
) : MessagePayload {
    override fun validate() {
        if (token.bytes.size != 32) throw ProtocolException(ErrorCode.TOKEN_INVALID, "token must be 32 bytes")
        if (deviceName.isEmpty() || deviceName.length > 64) throw ProtocolException.invalid("bad deviceName")
    }
}

@Serializable
data class PairConfirmCode(val code: String, val timeoutSec: Int) : MessagePayload {
    override fun validate() {
        if (code.length != 6 || !code.all { it in '0'..'9' }) throw ProtocolException.invalid("code must be 6 digits")
    }
}

@Serializable
data class PairOk(val macDeviceId: PBUUID, val macName: String, val port: Int) : MessagePayload

@Serializable(with = PairDeniedReasonSerializer::class)
enum class PairDeniedReason(override val wire: String) : WireEnum {
    USER_DENIED("user_denied"), TIMEOUT("timeout"), TOKEN_INVALID("token_invalid"), TOKEN_EXPIRED("token_expired"),
    RATE_LIMITED("rate_limited"), DEVICE_EXISTS("device_exists"), UNKNOWN("unknown")
}
object PairDeniedReasonSerializer :
    LenientEnumSerializer<PairDeniedReason>("PairDeniedReason", PairDeniedReason.entries, PairDeniedReason.UNKNOWN)

@Serializable data class PairDenied(val reason: PairDeniedReason) : MessagePayload
@Serializable data class PairRevoked(val reason: String) : MessagePayload
@Serializable data object PairUnpair : MessagePayload

// ---------------------------------------------------------------- Status / find phone (§6, §7)

@Serializable
data class StatusDevice(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val sdkInt: Int,
) : MessagePayload

@Serializable(with = PluggedSerializer::class)
enum class Plugged(override val wire: String) : WireEnum {
    AC("ac"), USB("usb"), WIRELESS("wireless"), DOCK("dock"), NONE("none"), UNKNOWN("unknown")
}
object PluggedSerializer : LenientEnumSerializer<Plugged>("Plugged", Plugged.entries, Plugged.UNKNOWN)

@Serializable
data class StatusBattery(val level: Int, val charging: Boolean, val plugged: Plugged) : MessagePayload {
    override fun validate() {
        if (level !in 0..100) throw ProtocolException.invalid("battery level out of range")
    }
}

@Serializable
data class PhoneRing(val durationSec: Int) : MessagePayload {
    override fun validate() {
        if (durationSec !in ALLOWED_DURATIONS) throw ProtocolException.invalid("durationSec must be one of $ALLOWED_DURATIONS")
    }
    companion object { val ALLOWED_DURATIONS = setOf(15, 30, 60, 120) }
}

@Serializable data object PhoneRingStop : MessagePayload

@Serializable(with = RingStopReasonSerializer::class)
enum class RingStopReason(override val wire: String) : WireEnum {
    STOPPED_ON_PHONE("stopped_on_phone"), STOPPED_REMOTELY("stopped_remotely"), TIMEOUT("timeout"),
    AUDIO_BLOCKED("audio_blocked"), UNKNOWN("unknown")
}
object RingStopReasonSerializer :
    LenientEnumSerializer<RingStopReason>("RingStopReason", RingStopReason.entries, RingStopReason.UNKNOWN)

@Serializable
data class PhoneRingState(val ringing: Boolean, val remainingSec: Int, val reason: RingStopReason? = null) :
    MessagePayload

// ---------------------------------------------------------------- Clipboard (§8)

@Serializable
data class ClipboardSet(
    val originDeviceId: PBUUID,
    val hash: String,
    val mime: String = "text/plain",
    val text: String,
) : MessagePayload {
    override fun validate() {
        if (mime != "text/plain") throw ProtocolException.invalid("unsupported mime $mime")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) throw ProtocolException.invalid("clipboard text too large")
        if (hash != hashOf(text)) throw ProtocolException.invalid("clipboard hash mismatch")
    }

    companion object {
        const val MAX_TEXT_BYTES = 1024 * 1024
        fun hashOf(text: String): String =
            Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
        fun of(originDeviceId: java.util.UUID, text: String) =
            ClipboardSet(originDeviceId = originDeviceId, hash = hashOf(text), text = text)
    }
}

// ---------------------------------------------------------------- Files (§9)

@Serializable
data class FileOffer(
    val transferId: PBUUID,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
    val chunkSize: Int = PBConstants.MAX_CHUNK_PAYLOAD,
    val modifiedAt: Long? = null,
) : MessagePayload {
    override fun validate() {
        if (size < 0) throw ProtocolException.invalid("negative size")
        if (!Hex.isSha256(sha256)) throw ProtocolException.invalid("bad sha256")
        if (chunkSize !in 1..PBConstants.MAX_CHUNK_PAYLOAD) throw ProtocolException.invalid("bad chunkSize")
        if (!isSafeFileName(name)) throw ProtocolException.invalid("unsafe file name")
    }

    companion object {
        fun isSafeFileName(n: String) = n.isNotEmpty() && n.toByteArray(Charsets.UTF_8).size <= 255 &&
            '/' !in n && '\u0000' !in n && n != "." && n != ".."
    }
}

@Serializable
data class FileAccept(val transferId: PBUUID, val offset: Long) : MessagePayload {
    override fun validate() { if (offset < 0) throw ProtocolException.invalid("negative offset") }
}

@Serializable(with = FileRejectReasonSerializer::class)
enum class FileRejectReason(override val wire: String) : WireEnum {
    USER_DECLINED("user_declined"), INSUFFICIENT_SPACE("insufficient_space"), TOO_LARGE("too_large"),
    BUSY("busy"), UNKNOWN("unknown")
}
object FileRejectReasonSerializer :
    LenientEnumSerializer<FileRejectReason>("FileRejectReason", FileRejectReason.entries, FileRejectReason.UNKNOWN)

@Serializable data class FileReject(val transferId: PBUUID, val reason: FileRejectReason) : MessagePayload
@Serializable data class FileAck(val transferId: PBUUID, val offset: Long) : MessagePayload
@Serializable data class FileNack(val transferId: PBUUID, val offset: Long) : MessagePayload
@Serializable data class FilePause(val transferId: PBUUID) : MessagePayload
@Serializable data class FileResume(val transferId: PBUUID) : MessagePayload
@Serializable data class FileCancel(val transferId: PBUUID, val reason: String) : MessagePayload
@Serializable data class FileDone(val transferId: PBUUID) : MessagePayload

@Serializable(with = FileResultErrorSerializer::class)
enum class FileResultError(override val wire: String) : WireEnum {
    HASH_MISMATCH("hash_mismatch"), SIZE_MISMATCH("size_mismatch"), IO_ERROR("io_error"), UNKNOWN("unknown")
}
object FileResultErrorSerializer :
    LenientEnumSerializer<FileResultError>("FileResultError", FileResultError.entries, FileResultError.UNKNOWN)

@Serializable
data class FileResult(
    val transferId: PBUUID,
    val ok: Boolean,
    val savedName: String? = null,
    val error: FileResultError? = null,
) : MessagePayload

// ---------------------------------------------------------------- Notifications (§10)

/** One mirrored notification. Also the payload of `notif.posted` (fields flattened). */
@Serializable
data class NotificationItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val postedAt: Long,
    val redacted: Boolean = false,
) : MessagePayload

@Serializable data class NotifRemoved(val key: String) : MessagePayload
@Serializable data class NotifDismiss(val key: String) : MessagePayload
@Serializable data class NotifSync(val items: List<NotificationItem>) : MessagePayload

@Serializable
data class NotifIcon(val pkg: String, val png: B64) : MessagePayload {
    override fun validate() {
        if (png.bytes.size > MAX_BYTES) throw ProtocolException.invalid("icon too large")
    }
    companion object { const val MAX_BYTES = 16 * 1024 }
}

// ---------------------------------------------------------------- Registry

/** Every known message type. Used by the router and by conformance tests. */
object Types {
    val HELLO = MessageType("hello", Hello.serializer())
    val AUTH = MessageType("auth", Auth.serializer())
    val READY = MessageType("ready", Ready.serializer())
    val PING = MessageType("ping", Ping.serializer())
    val PONG = MessageType("pong", Pong.serializer())
    val CAPABILITIES_CHANGED = MessageType("capabilities.changed", CapabilitiesChanged.serializer())
    val CAPABILITIES_ENABLED = MessageType("capabilities.enabled", CapabilitiesEnabled.serializer())
    val ERROR = MessageType("error", ErrorPayload.serializer())

    val PAIR_REQUEST = MessageType("pair.request", PairRequest.serializer())
    val PAIR_CONFIRM_CODE = MessageType("pair.confirm_code", PairConfirmCode.serializer())
    val PAIR_OK = MessageType("pair.ok", PairOk.serializer())
    val PAIR_DENIED = MessageType("pair.denied", PairDenied.serializer())
    val PAIR_REVOKED = MessageType("pair.revoked", PairRevoked.serializer())
    val PAIR_UNPAIR = MessageType("pair.unpair", PairUnpair.serializer())

    val STATUS_DEVICE = MessageType("status.device", StatusDevice.serializer())
    val STATUS_BATTERY = MessageType("status.battery", StatusBattery.serializer())
    val PHONE_RING = MessageType("phone.ring", PhoneRing.serializer())
    val PHONE_RING_STOP = MessageType("phone.ring.stop", PhoneRingStop.serializer())
    val PHONE_RING_STATE = MessageType("phone.ring.state", PhoneRingState.serializer())

    val CLIPBOARD_SET = MessageType("clipboard.set", ClipboardSet.serializer())

    val FILE_OFFER = MessageType("file.offer", FileOffer.serializer())
    val FILE_ACCEPT = MessageType("file.accept", FileAccept.serializer())
    val FILE_REJECT = MessageType("file.reject", FileReject.serializer())
    val FILE_ACK = MessageType("file.ack", FileAck.serializer())
    val FILE_NACK = MessageType("file.nack", FileNack.serializer())
    val FILE_PAUSE = MessageType("file.pause", FilePause.serializer())
    val FILE_RESUME = MessageType("file.resume", FileResume.serializer())
    val FILE_CANCEL = MessageType("file.cancel", FileCancel.serializer())
    val FILE_DONE = MessageType("file.done", FileDone.serializer())
    val FILE_RESULT = MessageType("file.result", FileResult.serializer())

    val NOTIF_POSTED = MessageType("notif.posted", NotificationItem.serializer())
    val NOTIF_REMOVED = MessageType("notif.removed", NotifRemoved.serializer())
    val NOTIF_DISMISS = MessageType("notif.dismiss", NotifDismiss.serializer())
    val NOTIF_SYNC = MessageType("notif.sync", NotifSync.serializer())
    val NOTIF_ICON = MessageType("notif.icon", NotifIcon.serializer())

    val all: List<MessageType<*>> = listOf(
        HELLO, AUTH, READY, PING, PONG, CAPABILITIES_CHANGED, CAPABILITIES_ENABLED, ERROR,
        PAIR_REQUEST, PAIR_CONFIRM_CODE, PAIR_OK, PAIR_DENIED, PAIR_REVOKED, PAIR_UNPAIR,
        STATUS_DEVICE, STATUS_BATTERY, PHONE_RING, PHONE_RING_STOP, PHONE_RING_STATE,
        CLIPBOARD_SET,
        FILE_OFFER, FILE_ACCEPT, FILE_REJECT, FILE_ACK, FILE_NACK, FILE_PAUSE, FILE_RESUME, FILE_CANCEL,
        FILE_DONE, FILE_RESULT,
        NOTIF_POSTED, NOTIF_REMOVED, NOTIF_DISMISS, NOTIF_SYNC, NOTIF_ICON,
    )

    val byType: Map<String, MessageType<*>> = all.associateBy { it.type }

    /** Types allowed on a pairing-mode connection (§2, §5). */
    val pairingModeTypes: Set<String> = setOf(
        HELLO.type, ERROR.type, PAIR_REQUEST.type, PAIR_CONFIRM_CODE.type, PAIR_OK.type, PAIR_DENIED.type,
    )
}
