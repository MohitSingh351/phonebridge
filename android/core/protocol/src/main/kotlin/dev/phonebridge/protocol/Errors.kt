package dev.phonebridge.protocol

import kotlinx.serialization.Serializable

/** Error codes from PROTOCOL.md §11. [closeCode] is the WebSocket close code used when fatal. */
@Serializable(with = ErrorCodeSerializer::class)
enum class ErrorCode(override val wire: String, val closeCode: Int?) : WireEnum {
    PAIRED("paired", 4000),
    AUTH_FAILED("auth_failed", 4001),
    VERSION_MISMATCH("version_mismatch", 4002),
    REVOKED("revoked", 4003),
    SEQUENCE_VIOLATION("sequence_violation", 4004),
    TOKEN_INVALID("token_invalid", 4005),
    TOKEN_EXPIRED("token_expired", 4006),
    RATE_LIMITED("rate_limited", 4007),
    FRAME_TOO_LARGE("frame_too_large", 4008),
    NOT_ALLOWED_IN_MODE("not_allowed_in_mode", 4009),
    HEARTBEAT_TIMEOUT("heartbeat_timeout", 4010),
    INVALID_PAYLOAD("invalid_payload", null),
    UNSUPPORTED_TYPE("unsupported_type", null),
    FEATURE_DISABLED("feature_disabled", null),
    PERMISSION_MISSING("permission_missing", null),
    TRANSFER_FAILED("transfer_failed", null),
    UNKNOWN("unknown", null);

    val isFatal: Boolean get() = closeCode != null && this != PAIRED

    companion object {
        fun fromCloseCode(code: Int): ErrorCode? = entries.firstOrNull { it.closeCode == code }
    }
}

object ErrorCodeSerializer : LenientEnumSerializer<ErrorCode>("ErrorCode", ErrorCode.entries, ErrorCode.UNKNOWN)

/** A protocol violation detected while decoding or validating a message. */
class ProtocolException(val code: ErrorCode, message: String) : Exception("${code.wire}: $message") {
    companion object {
        fun invalid(message: String) = ProtocolException(ErrorCode.INVALID_PAYLOAD, message)
    }
}
