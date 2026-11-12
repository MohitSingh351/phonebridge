package dev.phonebridge.transport

import dev.phonebridge.protocol.CapabilitiesEnabled
import dev.phonebridge.protocol.CapabilityState
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.EnvelopeCodec
import dev.phonebridge.protocol.ErrorCode
import dev.phonebridge.protocol.ErrorPayload
import dev.phonebridge.protocol.HelloMode
import dev.phonebridge.protocol.Hex
import dev.phonebridge.protocol.ProtocolCrypto
import dev.phonebridge.protocol.Ready
import dev.phonebridge.protocol.Types
import dev.phonebridge.security.MacStore
import dev.phonebridge.security.PairedMac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.UUID

/**
 * Reconnection behaviour against a fake Mac over real WebSockets (plain ws; TLS pinning is covered
 * separately). Backoff is shortened so the tests run fast.
 */
class ConnectionManagerTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val macId = UUID.randomUUID()
    private val online = MutableStateFlow(true)
    private val phone = PhoneInfo(UUID.randomUUID(), "Pixel", "Google", "Pixel", "17", 37, "1")

    private inner class Store(var port: Int) : MacStore {
        var mac: PairedMac? = PairedMac(macId, "Mac", Hex.encode(ByteArray(32)), "127.0.0.1", port, "alias")
        val addressUpdates = mutableListOf<Int>()
        override fun pairedMac() = mac?.copy(port = port)
        override fun updateAddress(host: String, port: Int) { this.port = port; addressUpdates += port }
        override fun clear() { mac = null }
    }

    private val connector = object : Connector {
        val signer = SoftSigner()
        override fun signer(mac: PairedMac): Signer = signer
        override fun open(host: String, port: Int, mac: PairedMac, client: SessionClient, scope: CoroutineScope,
                          onEvent: suspend (ClientEvent) -> Unit) =
            WsConnection(OkHttpClient(), "http://$host:$port/v1", client, scope, onEvent)
    }

    /** One fake-Mac session: hello → (auth) → ready, then optionally misbehave. */
    private fun fakeMac(after: (WebSocket) -> Unit = {}, revoke: Boolean = false) =
        MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            var seq = 1L
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (revoke) {
                    webSocket.send(EnvelopeCodec.encode(Envelope.make(Types.ERROR, ErrorPayload(ErrorCode.REVOKED, "unknown device", true), seq++)))
                    webSocket.close(4003, "revoked")
                    return
                }
                webSocket.send(EnvelopeCodec.encode(SessionClient.hello(HelloMode.SESSION, macId, ProtocolCrypto.randomBytes(32), seq++)))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null) // complete the close handshake like a real server
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val env = EnvelopeCodec.decode(text)
                if (env.type == Types.AUTH.type) {
                    val caps = env.decode(Types.AUTH).capabilities
                    val enabled = caps.filter { it.state == CapabilityState.AVAILABLE }.map { it.id }
                    webSocket.send(EnvelopeCodec.encode(Envelope.make(Types.READY, Ready(UUID.randomUUID(), enabled), seq++, re = env.id)))
                    after(webSocket)
                }
                if (env.type == Types.CAPABILITIES_CHANGED.type) {
                    val enabled = env.decode(Types.CAPABILITIES_CHANGED).capabilities.filter { it.state == CapabilityState.AVAILABLE }.map { it.id }
                    webSocket.send(EnvelopeCodec.encode(Envelope.make(Types.CAPABILITIES_ENABLED, CapabilitiesEnabled(enabled), seq++, re = env.id)))
                }
            }
        }).build()

    private fun manager(store: MacStore, locator: MacLocator = MacLocator { null }, features: List<Feature> = emptyList()) =
        ConnectionManager(store, { phone }, scope, connector, locator, online, backoff = { 50L }, features = features)

    /** A feature whose permission can be granted/revoked mid-session. */
    private class ToggleFeature : Feature {
        override val id = "notifications"
        @Volatile var granted = false
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val ends = java.util.concurrent.atomic.AtomicInteger()
        override fun state() = if (granted) CapabilityState.AVAILABLE else CapabilityState.PERMISSION_MISSING
        override suspend fun onSessionStarted(link: FeatureLink) { starts.incrementAndGet() }
        override fun onSessionEnded() { ends.incrementAndGet() }
    }

    private suspend fun ConnectionManager.awaitState(timeoutMs: Long = 5_000, p: (LinkState) -> Boolean) =
        withTimeout(timeoutMs) { state.first(p) }

    @BeforeEach fun up() = server.start()
    @AfterEach fun down() { scope.cancel(); server.close() }

    @Test
    fun `reconnects right away after a working session drops`() = runBlocking {
        server.enqueue(fakeMac(after = { it.close(1001, "mac restarting") }))
        server.enqueue(fakeMac())
        val m = manager(Store(server.port))
        m.start()
        m.awaitState { it is LinkState.Connected }
        delay(300)
        m.awaitState { it is LinkState.Connected }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `permission granted then revoked mid-session starts and stops the feature without reconnecting`() = runBlocking {
        server.enqueue(fakeMac())
        val f = ToggleFeature()
        val m = manager(Store(server.port), features = listOf(f))
        m.start()
        m.awaitState { it is LinkState.Connected }
        assertEquals(0, f.starts.get())

        f.granted = true
        m.capabilitiesChanged()
        m.awaitState { it is LinkState.Connected && "notifications" in it.enabled }
        withTimeout(2_000) { while (f.starts.get() == 0) delay(10) }

        f.granted = false
        m.capabilitiesChanged()
        m.awaitState { it is LinkState.Connected && "notifications" !in it.enabled }
        withTimeout(2_000) { while (f.ends.get() == 0) delay(10) }
        assertEquals(1, f.starts.get())
        assertEquals(1, server.requestCount) // same session throughout
    }

    @Test
    fun `waits for Wi-Fi instead of hammering the network`() = runBlocking {
        online.value = false
        server.enqueue(fakeMac())
        val m = manager(Store(server.port))
        m.start()
        m.awaitState { it is LinkState.NoNetwork }
        delay(300)
        assertEquals(0, server.requestCount, "no attempts while offline")
        online.value = true
        m.awaitState { it is LinkState.Connected }
        Unit
    }

    @Test
    fun `losing Wi-Fi drops the live connection immediately, regaining it reconnects`() = runBlocking {
        server.enqueue(fakeMac())
        server.enqueue(fakeMac())
        val m = manager(Store(server.port))
        m.start()
        m.awaitState { it is LinkState.Connected }
        online.value = false
        m.awaitState(2_000) { it is LinkState.NoNetwork }
        online.value = true
        m.awaitState { it is LinkState.Connected }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `revoked by the Mac stops reconnecting`() = runBlocking {
        server.enqueue(fakeMac(revoke = true))
        server.enqueue(fakeMac())
        val m = manager(Store(server.port))
        m.start()
        m.awaitState { it is LinkState.Removed }
        delay(500)
        assertEquals(1, server.requestCount, "must not retry after removal")
        assertTrue(!m.isRunning)
    }

    @Test
    fun `finds the Mac at a new address via discovery`() = runBlocking {
        server.enqueue(fakeMac())
        val dead = ServerSocket(0).use { it.localPort } // nothing listens here any more
        val store = Store(dead)
        var lookups = 0
        val m = manager(store, MacLocator { lookups++; MacLocator.Address("127.0.0.1", server.port) })
        m.start()
        m.awaitState { it is LinkState.Connected }
        assertEquals(listOf(server.port), store.addressUpdates, "new address must be remembered")
        assertEquals(1, lookups)
    }

    @Test
    fun `retryNow skips the backoff`() = runBlocking {
        val dead = ServerSocket(0).use { it.localPort }
        val store = Store(dead)
        val m = ConnectionManager(store, { phone }, scope, connector, { null }, online, backoff = { 60_000L })
        m.start()
        m.awaitState { it is LinkState.Waiting }
        store.port = server.port
        server.enqueue(fakeMac())
        m.retryNow()
        m.awaitState(3_000) { it is LinkState.Connected }
        Unit
    }

    @Test
    fun `stop leaves the Mac alone`() = runBlocking {
        server.enqueue(fakeMac())
        val m = manager(Store(server.port))
        m.start()
        m.awaitState { it is LinkState.Connected }
        m.stop()
        assertEquals(LinkState.Stopped, m.state.value)
        delay(300)
        assertEquals(1, server.requestCount)
    }
}
