package dev.phonebridge.transport

import dev.phonebridge.protocol.Hex
import dev.phonebridge.security.DeviceIdentity
import dev.phonebridge.security.PairedMac
import dev.phonebridge.security.PairingStore
import dev.phonebridge.security.PinnedTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val macName: String) : PairingState
    /** Show this code; the user compares it with the Mac and taps Allow there. */
    data class ConfirmCode(val macName: String, val code: String) : PairingState
    data class Paired(val macName: String) : PairingState
    data class Failed(val reason: String) : PairingState
}

/** Runs one pairing attempt from a scanned QR code (PROTOCOL.md §5.2). */
class PairingController(
    private val store: PairingStore,
    private val phone: () -> PhoneInfo,
    private val scope: CoroutineScope,
    private val onPaired: () -> Unit,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private var job: Job? = null
    private var conn: WsConnection? = null

    fun start(qr: PairingQr) {
        if (job?.isActive == true) return
        _state.value = PairingState.Connecting(qr.macName)
        job = scope.launch(Dispatchers.IO) {
            // A fresh, unique key for this Mac. Created before connecting: it is our TLS client certificate.
            val alias = DeviceIdentity.aliasFor(qr.macId.toString())
            val identity = DeviceIdentity.create(alias)
            val signer = object : Signer {
                override val fingerprint = identity.fingerprint
                override fun sign(message: ByteArray) = identity.sign(message)
            }
            var result: PairingState? = null
            val client = SessionClient.pairing(qr, phone(), signer)
            val c = WsConnection(qr.host, qr.port, PinnedTls(identity, qr.macFingerprint), client, scope) { e ->
                when (e) {
                    is ClientEvent.ShowCode -> _state.value = PairingState.ConfirmCode(qr.macName, e.code)
                    is ClientEvent.Paired -> {
                        store.clear() // replaces any previous pairing (and its key)
                        store.save(PairedMac(e.macId, e.macName, Hex.encode(qr.macFingerprint), qr.host, e.port, alias))
                        result = PairingState.Paired(e.macName)
                    }
                    is ClientEvent.PairingFailed -> result = PairingState.Failed(e.reason)
                    is ClientEvent.Rejected -> result = PairingState.Failed(e.code.wire)
                    else -> Unit
                }
            }
            conn = c
            c.open()
            val end = c.ended.await()
            val final = result ?: PairingState.Failed(
                when (end) {
                    is ConnectionEnd.Failed -> if (end.isTlsRejection) "mac_key_mismatch_or_expired" else "mac_unreachable"
                    is ConnectionEnd.Closed -> "closed_${end.code}"
                },
            )
            if (final !is PairingState.Paired) DeviceIdentity.delete(alias)
            _state.value = final
            if (final is PairingState.Paired) onPaired()
        }
    }

    fun cancel() {
        conn?.close(1000, "cancelled")
        job?.cancel()
        _state.value = PairingState.Idle
    }

    fun reset() { _state.value = PairingState.Idle }

    companion object {
        /** Human text for failure reasons. */
        fun describe(reason: String): String = when (reason) {
            "user_denied" -> "Pairing was declined on the Mac."
            "timeout" -> "Nobody tapped Allow on the Mac in time."
            "code_mismatch" -> "The codes didn't match — pairing stopped for safety. Try again."
            "token_invalid", "token_expired" -> "This QR code has expired or was already used. Make a new one on the Mac."
            "rate_limited" -> "Too many attempts. Wait a minute, then make a new QR code."
            "mac_unreachable" -> "Couldn't reach the Mac. Check both are on the same Wi-Fi and PhoneBridge is running."
            "mac_key_mismatch_or_expired" -> "The Mac refused the connection. Make a new QR code and scan it again."
            "revoked" -> "The Mac closed pairing. Make a new QR code and try again."
            else -> "Pairing didn't complete ($reason)."
        }
    }
}
