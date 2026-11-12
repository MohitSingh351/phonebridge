package dev.phonebridge.transport

import dev.phonebridge.protocol.ErrorCode
import dev.phonebridge.protocol.Types
import dev.phonebridge.security.DeviceIdentity
import dev.phonebridge.security.PairedMac
import dev.phonebridge.security.PairingStore
import dev.phonebridge.security.PinnedTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

/** What the UI shows about the link to the Mac. */
sealed interface LinkState {
    data object NotPaired : LinkState
    data class Connecting(val macName: String, val attempt: Int) : LinkState
    data class Connected(val macName: String, val enabled: List<String>) : LinkState
    data class Waiting(val macName: String, val reason: String, val retryAtMs: Long) : LinkState
    /** The Mac removed this phone. Stops reconnecting until the user pairs again. */
    data class Removed(val macName: String) : LinkState
}

/**
 * Keeps the phone connected to its paired Mac: connect → handshake → stay up → on drop, back off
 * (1 s → 30 s with jitter) and retry. Phase 2 adds the foreground service, network callbacks and
 * discovery on top of this loop.
 */
class ConnectionManager(
    private val store: PairingStore,
    private val phone: () -> PhoneInfo,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<LinkState>(if (store.pairedMac() == null) LinkState.NotPaired else LinkState.Waiting("Mac", "starting", 0))
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private var loop: Job? = null
    @Volatile private var current: WsConnection? = null
    @Volatile private var currentClient: SessionClient? = null
    private var wake = MutableStateFlow(0)

    fun start() {
        if (loop?.isActive == true) return
        val mac = store.pairedMac() ?: run { _state.value = LinkState.NotPaired; return }
        loop = scope.launch { runLoop(mac) }
    }

    fun stop() {
        loop?.cancel(); loop = null
        current?.close(1000, "stopped")
    }

    /** Skip the current backoff wait (e.g. the app came to the foreground). */
    fun retryNow() { wake.value++ }

    /** User chose "Unpair": tell the Mac if connected, then forget everything locally. */
    fun unpair() {
        val conn = current
        val client = currentClient
        if (conn != null && client != null) conn.submit { client.outgoing(Types.PAIR_UNPAIR, dev.phonebridge.protocol.PairUnpair) }
        scope.launch {
            delay(300)
            stop()
            store.clear()
            _state.value = LinkState.NotPaired
        }
    }

    private suspend fun runLoop(mac: PairedMac) {
        var attempt = 0
        while (scope.isActive) {
            val identity = DeviceIdentity.load(mac.keyAlias)
            if (identity == null) { // key lost (e.g. backup restore): pairing is unusable
                _state.value = LinkState.Removed(mac.name)
                return
            }
            attempt++
            _state.value = LinkState.Connecting(mac.name, attempt)
            val latest = store.pairedMac() ?: return
            val signer = object : Signer {
                override val fingerprint = identity.fingerprint
                override fun sign(message: ByteArray) = identity.sign(message)
            }
            val client = SessionClient.session(mac.macId, phone(), signer)
            var removed = false
            val conn = WsConnection(latest.host, latest.port, PinnedTls(identity, mac.fingerprint), client, scope) { e ->
                when (e) {
                    is ClientEvent.Ready -> { attempt = 0; _state.value = LinkState.Connected(mac.name, e.enabled) }
                    ClientEvent.Revoked -> removed = true
                    is ClientEvent.Rejected -> if (e.code == ErrorCode.REVOKED) removed = true
                    else -> Unit // feature messages: routed from Phase 3 on
                }
            }
            current = conn; currentClient = client
            conn.open()
            val end = conn.ended.await()
            current = null; currentClient = null

            if (removed || (end is ConnectionEnd.Closed && end.code == ErrorCode.REVOKED.closeCode)) {
                _state.value = LinkState.Removed(mac.name)
                return
            }
            val reason = when (end) {
                is ConnectionEnd.Closed -> "disconnected (${end.code})"
                is ConnectionEnd.Failed -> if (end.isTlsRejection) "Mac refused the secure connection" else "Mac not reachable"
            }
            val backoff = backoffMs(attempt)
            _state.value = LinkState.Waiting(mac.name, reason, System.currentTimeMillis() + backoff)
            waitOrWake(backoff)
        }
    }

    private suspend fun waitOrWake(ms: Long) {
        val start = wake.value
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline && wake.value == start) delay(200)
    }

    companion object {
        /** 1 s, 2 s, 4 s … capped at 30 s, ±20 % jitter. */
        fun backoffMs(attempt: Int, random: Random = Random.Default): Long {
            val base = min(30_000L, 1000L shl (attempt - 1).coerceIn(0, 5))
            return (base * (0.8 + 0.4 * random.nextDouble())).toLong()
        }
    }
}
