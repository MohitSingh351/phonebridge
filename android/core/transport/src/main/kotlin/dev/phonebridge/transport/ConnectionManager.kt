package dev.phonebridge.transport

import dev.phonebridge.protocol.ErrorCode
import dev.phonebridge.protocol.Features
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.protocol.PairUnpair
import dev.phonebridge.protocol.Types
import dev.phonebridge.security.MacStore
import dev.phonebridge.security.PairedMac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/** What the UI and the service notification show about the link to the Mac. */
sealed interface LinkState {
    data object NotPaired : LinkState
    data object Stopped : LinkState
    data class Connecting(val macName: String, val attempt: Int) : LinkState
    data class Connected(val macName: String, val enabled: List<String>) : LinkState
    data class Waiting(val macName: String, val reason: String, val retryAtMs: Long) : LinkState
    data class NoNetwork(val macName: String) : LinkState
    /** The Mac removed this phone. Stops reconnecting until the user pairs again. */
    data class Removed(val macName: String) : LinkState
}

/** Opens connections for a paired Mac. Production: Keystore key + pinned TLS. Tests: plain WebSocket. */
interface Connector {
    /** Null when the device key is gone (e.g. data restored to a new phone) — pairing is unusable. */
    fun signer(mac: PairedMac): Signer?
    fun open(host: String, port: Int, mac: PairedMac, client: SessionClient, scope: CoroutineScope,
             onEvent: suspend (ClientEvent) -> Unit): WsConnection
}

/** Finds the Mac on the LAN when its address changed (DNS-SD). */
fun interface MacLocator {
    data class Address(val host: String, val port: Int)
    suspend fun locate(macId: UUID): Address?
}

/**
 * Keeps the phone connected to its paired Mac:
 *  - waits while there is no Wi-Fi/Ethernet, connects as soon as there is;
 *  - drops the connection immediately when the network goes away;
 *  - on failure tries rediscovery (the Mac's IP may have changed), then backs off 1 s → 30 s with jitter;
 *  - stops for good when the Mac removes this phone.
 * The foreground service (app module) keeps the process alive; this class owns only the logic.
 */
class ConnectionManager(
    private val store: MacStore,
    private val phone: () -> PhoneInfo,
    private val scope: CoroutineScope,
    private val connector: Connector,
    private val locator: MacLocator,
    private val online: StateFlow<Boolean>,
    private val backoff: (Int) -> Long = { backoffMs(it) },
    private val features: List<Feature> = emptyList(),
) {
    private val _state = MutableStateFlow<LinkState>(if (store.pairedMac() == null) LinkState.NotPaired else LinkState.Stopped)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private var loop: Job? = null
    private var watcher: Job? = null
    @Volatile private var current: WsConnection? = null
    @Volatile private var currentClient: SessionClient? = null
    private val wake = MutableStateFlow(0)

    val isRunning get() = loop?.isActive == true

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        if (store.pairedMac() == null) { _state.value = LinkState.NotPaired; return }
        loop = scope.launch { runLoop() }
        watcher = scope.launch {
            var wasOnline = online.value
            online.collect { now ->
                if (!now && wasOnline) current?.close(1001, "network lost")   // don't wait for TCP timeouts
                if (now && !wasOnline) retryNow()
                wasOnline = now
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel(); loop = null
        watcher?.cancel(); watcher = null
        current?.close(1000, "stopped")
        _state.value = if (store.pairedMac() == null) LinkState.NotPaired else LinkState.Stopped
    }

    /** Skip the current backoff wait (app opened, screen on, network back). */
    fun retryNow() { wake.value++ }

    /** User chose "Unpair": tell the Mac if connected, then forget everything locally. */
    fun unpair() {
        val conn = current
        val client = currentClient
        if (conn != null && client != null) conn.submit { client.outgoing(Types.PAIR_UNPAIR, PairUnpair) }
        scope.launch {
            delay(300)
            stop()
            store.clear()
            _state.value = LinkState.NotPaired
        }
    }

    /** Send a feature message on the live session (dropped if not connected). */
    fun send(build: (SessionClient) -> List<ClientAction>) {
        val conn = current ?: return
        val client = currentClient ?: return
        conn.submit { build(client) }
    }

    private suspend fun runLoop() {
        var attempt = 0
        var failuresHere = 0
        while (scope.isActive) {
            val mac = store.pairedMac() ?: run { _state.value = LinkState.NotPaired; return }

            if (!online.value) {
                _state.value = LinkState.NoNetwork(mac.name)
                online.first { it }
                continue
            }
            val signer = connector.signer(mac) ?: run { _state.value = LinkState.Removed(mac.name); return }

            attempt++
            _state.value = LinkState.Connecting(mac.name, attempt)
            val client = SessionClient.session(mac.macId, phone(), signer, capabilities = { features.map { it.capability() } })
            var removed = false
            var wasReady = false
            var active: List<Feature> = emptyList()
            lateinit var conn: WsConnection
            val link = object : FeatureLink {
                override fun <P : MessagePayload> send(mt: MessageType<P>, payload: P): Boolean {
                    if (!client.isReady || client.isClosed) return false
                    conn.submit { client.outgoing(mt, payload) }
                    return true
                }
            }
            conn = connector.open(mac.host, mac.port, mac, client, scope) { e ->
                when (e) {
                    is ClientEvent.Ready -> {
                        wasReady = true; attempt = 0; failuresHere = 0
                        _state.value = LinkState.Connected(mac.name, e.enabled)
                        active = features.filter { it.id in e.enabled }
                        active.forEach { runCatching { it.onSessionStarted(link) } }
                    }
                    ClientEvent.Revoked -> removed = true
                    is ClientEvent.Rejected -> if (e.code == ErrorCode.REVOKED) removed = true
                    is ClientEvent.Message -> {
                        val owner = Features.idFor(e.envelope.type)
                        active.firstOrNull { it.id == owner }?.let { f -> runCatching { f.onMessage(e.envelope) } }
                    }
                    else -> Unit
                }
            }
            current = conn; currentClient = client
            conn.open()
            val end = conn.ended.await()
            current = null; currentClient = null
            active.forEach { runCatching { it.onSessionEnded() } }

            if (removed || (end is ConnectionEnd.Closed && end.code == ErrorCode.REVOKED.closeCode)) {
                _state.value = LinkState.Removed(mac.name)
                return
            }
            if (wasReady) continue // a working session dropped: reconnect right away (attempt was reset)

            val unreachable = end is ConnectionEnd.Failed && !end.isTlsRejection
            if (unreachable) failuresHere++
            // The Mac's DHCP address may have changed: look it up after the cached address fails twice.
            if (unreachable && failuresHere >= 2 && online.value) {
                val found = locator.locate(mac.macId)
                if (found != null && (found.host != mac.host || found.port != mac.port)) {
                    store.updateAddress(found.host, found.port)
                    failuresHere = 0
                    continue
                }
            }
            val reason = when (end) {
                is ConnectionEnd.Closed -> "disconnected"
                is ConnectionEnd.Failed -> if (end.isTlsRejection) "Mac refused the secure connection" else "Mac not reachable"
            }
            val wait = backoff(attempt)
            _state.value = LinkState.Waiting(mac.name, reason, System.currentTimeMillis() + wait)
            waitOrWake(wait)
        }
    }

    private suspend fun waitOrWake(ms: Long) {
        val start = wake.value
        withTimeoutOrNull(ms) { wake.first { it != start } }
    }

    companion object {
        /** 1 s, 2 s, 4 s … capped at 30 s, ±20 % jitter. */
        fun backoffMs(attempt: Int, random: Random = Random.Default): Long {
            val base = min(30_000L, 1000L shl (attempt - 1).coerceIn(0, 5))
            return (base * (0.8 + 0.4 * random.nextDouble())).toLong()
        }
    }
}
