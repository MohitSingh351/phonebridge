package dev.phonebridge.transport

import dev.phonebridge.protocol.Auth
import dev.phonebridge.protocol.B64
import dev.phonebridge.protocol.Capability
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.EnvelopeCodec
import dev.phonebridge.protocol.ErrorCode
import dev.phonebridge.protocol.ErrorPayload
import dev.phonebridge.protocol.Hello
import dev.phonebridge.protocol.HelloMode
import dev.phonebridge.protocol.InboundSequence
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.protocol.OutboundSequence
import dev.phonebridge.protocol.PBConstants
import dev.phonebridge.protocol.PairRequest
import dev.phonebridge.protocol.Ping
import dev.phonebridge.protocol.Pong
import dev.phonebridge.protocol.ProtocolCrypto
import dev.phonebridge.protocol.ProtocolException
import dev.phonebridge.protocol.Types
import java.util.UUID

/** What the phone signs with: its Keystore key (see core:security DeviceIdentity). */
interface Signer {
    val fingerprint: ByteArray
    fun sign(message: ByteArray): ByteArray
}

data class PhoneInfo(
    val deviceId: UUID,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersion: String,
)

sealed interface ClientEvent {
    data class Ready(val sessionId: UUID, val enabled: List<String>, val heartbeatSec: Int) : ClientEvent
    data class ShowCode(val code: String) : ClientEvent
    data class Paired(val macId: UUID, val macName: String, val port: Int) : ClientEvent
    data class PairingFailed(val reason: String) : ClientEvent
    /** The Mac refused us with a fatal error (or we detected a protocol violation). */
    data class Rejected(val code: ErrorCode, val message: String) : ClientEvent
    data object Revoked : ClientEvent
    /** A message for an enabled feature. */
    data class Message(val envelope: Envelope) : ClientEvent
    /** The Mac answered `capabilities.changed` with a new enabled set (PROTOCOL.md §4.5). */
    data class EnabledChanged(val enabled: List<String>) : ClientEvent
}

sealed interface ClientAction {
    data class Send(val envelope: Envelope) : ClientAction
    data class Close(val code: Int, val reason: String) : ClientAction
    data class Emit(val event: ClientEvent) : ClientAction
}

/**
 * The phone side of one connection as a pure state machine (PROTOCOL.md §4, §5) — the mirror of the Mac's
 * SessionHandler. No I/O: the connection feeds it frames and performs the returned actions.
 */
class SessionClient private constructor(
    private val mode: Mode,
    private val phone: PhoneInfo,
    private val signer: Signer,
    private val now: () -> Long,
    private val clientNonce: ByteArray,
) {
    sealed interface Mode {
        /** Normal reconnect to an already-paired Mac. */
        data class Session(val macId: UUID, val capabilities: () -> List<Capability>) : Mode
        /** First contact after scanning the QR code. */
        data class Pairing(val qr: PairingQr) : Mode
    }

    private enum class State { AWAITING_HELLO, AWAITING_READY, AWAITING_CONFIRM, AWAITING_APPROVAL, READY, CLOSED }

    private var state = State.AWAITING_HELLO
    private val inbound = InboundSequence()
    private val outbound = OutboundSequence()
    private var expectedCode: String? = null
    private var pairRequestId: UUID? = null
    private var heartbeatMs = PBConstants.DEFAULT_HEARTBEAT_SEC * 1000L
    private var lastInbound = now()
    private var lastPing = now()
    private val openedAt = now()

    val isReady get() = state == State.READY
    val isClosed get() = state == State.CLOSED

    fun receive(text: String): List<ClientAction> {
        if (state == State.CLOSED) return emptyList()
        lastInbound = now()
        val env = try {
            EnvelopeCodec.decode(text).also { inbound.accept(it.seq) }
        } catch (e: ProtocolException) {
            return fail(e.code, e.message ?: "protocol error")
        }
        // A fatal error from the Mac ends the session in any state.
        if (env.type == Types.ERROR.type) {
            val err = runCatching { env.decode(Types.ERROR) }.getOrNull()
                ?: return fail(ErrorCode.INVALID_PAYLOAD, "bad error message")
            if (!err.fatal) return emptyList()
            state = State.CLOSED
            val event = when {
                mode is Mode.Pairing -> ClientEvent.PairingFailed(err.code.wire)
                err.code == ErrorCode.REVOKED -> ClientEvent.Revoked
                else -> ClientEvent.Rejected(err.code, err.message ?: err.code.wire)
            }
            return listOf(ClientAction.Emit(event))
        }
        return when (state) {
            State.AWAITING_HELLO -> onHello(env)
            State.AWAITING_READY -> onReady(env)
            State.AWAITING_CONFIRM -> onConfirmCode(env)
            State.AWAITING_APPROVAL -> onPairingResult(env)
            State.READY -> onSessionMessage(env)
            State.CLOSED -> emptyList()
        }
    }

    /** Call about once a second: heartbeat pings and dead-connection detection. */
    fun tick(): List<ClientAction> {
        val t = now()
        return when (state) {
            State.READY -> when {
                t - lastInbound > 3 * heartbeatMs -> fail(ErrorCode.HEARTBEAT_TIMEOUT, "Mac stopped responding")
                t - lastPing >= heartbeatMs -> { lastPing = t; listOf(send(Types.PING, Ping)) }
                else -> emptyList()
            }
            State.AWAITING_HELLO, State.AWAITING_READY, State.AWAITING_CONFIRM ->
                if (t - openedAt > HANDSHAKE_TIMEOUT_MS) fail(ErrorCode.HEARTBEAT_TIMEOUT, "handshake timed out") else emptyList()
            State.AWAITING_APPROVAL ->
                if (t - openedAt > APPROVAL_TIMEOUT_MS) {
                    state = State.CLOSED
                    listOf(ClientAction.Close(1000, "approval timeout"), ClientAction.Emit(ClientEvent.PairingFailed("timeout")))
                } else emptyList()
            State.CLOSED -> emptyList()
        }
    }

    /** Send a feature message on a ready session. */
    fun <P : MessagePayload> outgoing(mt: MessageType<P>, payload: P, re: UUID? = null): List<ClientAction> =
        if (state == State.READY) listOf(send(mt, payload, re)) else emptyList()

    // ---------------------------------------------------------------- handlers

    private fun onHello(env: Envelope): List<ClientAction> {
        if (env.type != Types.HELLO.type) return fail(ErrorCode.NOT_ALLOWED_IN_MODE, "expected hello")
        val hello = try { env.decode(Types.HELLO) } catch (e: ProtocolException) { return fail(e.code, e.message ?: "") }
        if (PBConstants.PROTOCOL_VERSION !in hello.minProtocolVersion..hello.protocolVersion) {
            return fail(ErrorCode.VERSION_MISMATCH, "Mac speaks protocol ${hello.minProtocolVersion}..${hello.protocolVersion}")
        }
        return when (mode) {
            is Mode.Session -> {
                if (hello.deviceId != mode.macId) return fail(ErrorCode.AUTH_FAILED, "unexpected Mac id")
                if (hello.mode != HelloMode.SESSION) {
                    // The Mac no longer recognises our key: we were removed.
                    state = State.CLOSED
                    return listOf(ClientAction.Close(1000, "not trusted"), ClientAction.Emit(ClientEvent.Revoked))
                }
                val msg = ProtocolCrypto.authSigningBytes(hello.nonce.bytes, clientNonce, hello.deviceId, phone.deviceId)
                state = State.AWAITING_READY
                listOf(send(Types.AUTH, Auth(
                    deviceId = phone.deviceId, appVersion = phone.appVersion, clientNonce = B64(clientNonce),
                    sig = B64(signer.sign(msg)), capabilities = mode.capabilities(),
                ), re = env.id))
            }
            is Mode.Pairing -> {
                if (hello.deviceId != mode.qr.macId) return pairingFail("wrong_mac")
                if (hello.mode != HelloMode.PAIRING) return pairingFail("mac_not_in_pairing_mode")
                expectedCode = ProtocolCrypto.sasCode(mode.qr.macFingerprint, signer.fingerprint, hello.nonce.bytes)
                state = State.AWAITING_CONFIRM
                val req = send(Types.PAIR_REQUEST, PairRequest(
                    token = B64(mode.qr.token), deviceId = phone.deviceId, deviceName = phone.deviceName.take(64),
                    manufacturer = phone.manufacturer, model = phone.model, osVersion = phone.osVersion,
                    sdkInt = phone.sdkInt, appVersion = phone.appVersion,
                ), re = env.id)
                pairRequestId = req.envelope.id
                listOf(req)
            }
        }
    }

    private fun onReady(env: Envelope): List<ClientAction> {
        if (env.type != Types.READY.type) return fail(ErrorCode.NOT_ALLOWED_IN_MODE, "expected ready")
        val ready = try { env.decode(Types.READY) } catch (e: ProtocolException) { return fail(e.code, e.message ?: "") }
        heartbeatMs = ready.heartbeatSec * 1000L
        lastPing = now()
        state = State.READY
        return listOf(ClientAction.Emit(ClientEvent.Ready(ready.sessionId, ready.enabled, ready.heartbeatSec)))
    }

    private fun onConfirmCode(env: Envelope): List<ClientAction> {
        if (env.type != Types.PAIR_CONFIRM_CODE.type) return pairingFail("unexpected_${env.type}")
        val c = try { env.decode(Types.PAIR_CONFIRM_CODE) } catch (_: ProtocolException) { return pairingFail("bad_code") }
        // Both sides derive the code independently; a mismatch means someone is in the middle.
        if (c.code != expectedCode) return pairingFail("code_mismatch")
        state = State.AWAITING_APPROVAL
        return listOf(ClientAction.Emit(ClientEvent.ShowCode(c.code)))
    }

    private fun onPairingResult(env: Envelope): List<ClientAction> = when (env.type) {
        Types.PAIR_OK.type -> {
            val ok = try { env.decode(Types.PAIR_OK) } catch (_: ProtocolException) { null }
            val qr = (mode as Mode.Pairing).qr
            if (ok == null || ok.macDeviceId != qr.macId) pairingFail("bad_pair_ok")
            else {
                state = State.CLOSED
                listOf(ClientAction.Emit(ClientEvent.Paired(ok.macDeviceId, ok.macName, ok.port)))
            }
        }
        Types.PAIR_DENIED.type -> {
            val reason = runCatching { env.decode(Types.PAIR_DENIED).reason.wire }.getOrDefault("denied")
            state = State.CLOSED
            listOf(ClientAction.Emit(ClientEvent.PairingFailed(reason)))
        }
        else -> pairingFail("unexpected_${env.type}")
    }

    private fun onSessionMessage(env: Envelope): List<ClientAction> = when (env.type) {
        Types.PING.type -> listOf(send(Types.PONG, Pong, re = env.id))
        Types.PONG.type -> emptyList()
        Types.PAIR_REVOKED.type -> {
            state = State.CLOSED
            listOf(ClientAction.Emit(ClientEvent.Revoked))
        }
        Types.CAPABILITIES_ENABLED.type -> {
            val m = runCatching { env.decode(Types.CAPABILITIES_ENABLED) }.getOrNull()
            if (m == null) emptyList() else listOf(ClientAction.Emit(ClientEvent.EnabledChanged(m.enabled)))
        }
        else -> listOf(ClientAction.Emit(ClientEvent.Message(env)))
    }

    // ---------------------------------------------------------------- helpers

    private fun <P : MessagePayload> send(mt: MessageType<P>, p: P, re: UUID? = null) =
        ClientAction.Send(Envelope.make(mt, p, seq = outbound.take(), re = re))

    private fun fail(code: ErrorCode, message: String): List<ClientAction> {
        state = State.CLOSED
        val close = code.closeCode ?: ErrorCode.AUTH_FAILED.closeCode!!
        val event = if (mode is Mode.Pairing) ClientEvent.PairingFailed(code.wire) else ClientEvent.Rejected(code, message)
        return listOf(
            send(Types.ERROR, ErrorPayload(code, message, fatal = true)),
            ClientAction.Close(close, message),
            ClientAction.Emit(event),
        )
    }

    private fun pairingFail(reason: String): List<ClientAction> {
        state = State.CLOSED
        return listOf(ClientAction.Close(1000, reason), ClientAction.Emit(ClientEvent.PairingFailed(reason)))
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
        const val APPROVAL_TIMEOUT_MS = 75_000L // Mac times out at 60 s; allow for scan + network latency

        fun session(macId: UUID, phone: PhoneInfo, signer: Signer, capabilities: () -> List<Capability> = { emptyList() },
                    now: () -> Long = System::currentTimeMillis, clientNonce: ByteArray = ProtocolCrypto.randomBytes(32)) =
            SessionClient(Mode.Session(macId, capabilities), phone, signer, now, clientNonce)

        fun pairing(qr: PairingQr, phone: PhoneInfo, signer: Signer, now: () -> Long = System::currentTimeMillis) =
            SessionClient(Mode.Pairing(qr), phone, signer, now, ProtocolCrypto.randomBytes(32))

        /** Exposed for tests. */
        internal fun hello(mode: HelloMode, macId: UUID, nonce: ByteArray, seq: Long = 1) = Envelope.make(
            Types.HELLO, Hello(mode = mode, deviceId = macId, deviceName = "Mac", platform = "macos",
                appVersion = "1", nonce = B64(nonce), capabilities = emptyList()), seq = seq,
        )
    }
}
