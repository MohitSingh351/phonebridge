package dev.phonebridge.transport

import dev.phonebridge.protocol.EnvelopeCodec
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.HelloMode
import dev.phonebridge.protocol.Hex
import dev.phonebridge.protocol.PairConfirmCode
import dev.phonebridge.protocol.PairOk
import dev.phonebridge.protocol.ProtocolCrypto
import dev.phonebridge.protocol.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.jupiter.api.RepeatedTest
import java.util.Collections
import java.util.UUID

/**
 * Real WebSocket (no TLS) against a fake Mac. Regression test for the first on-device pairing:
 * the Mac sends `pair.ok` and immediately closes with 4000; the phone must still see `Paired`.
 */
class WsConnectionTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeEach fun start() = server.start()
    @AfterEach fun stop() = server.close()

    @RepeatedTest(25) // the original bug was a race; repeat to make it show up if it ever regresses
    fun `pair ok followed by an immediate close still completes pairing`() = runBlocking {
        val signer = SoftSigner()
        val macFp = ProtocolCrypto.randomBytes(32)
        val nonce = ProtocolCrypto.randomBytes(32)
        val qr = PairingQr("127.0.0.1", server.port, Hex.encode(macFp), ProtocolCrypto.randomBytes(32), UUID.randomUUID(), "Mac")
        val code = ProtocolCrypto.sasCode(macFp, signer.fingerprint, nonce)

        // Fake Mac: hello → (pair.request) → confirm code + pair.ok + close 4000, back to back.
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            var seq = 1L
            fun send(ws: WebSocket, e: Envelope) { ws.send(EnvelopeCodec.encode(e)) }
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(webSocket, SessionClient.hello(HelloMode.PAIRING, qr.macId, nonce, seq++))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val req = EnvelopeCodec.decode(text)
                if (req.type != Types.PAIR_REQUEST.type) return
                send(webSocket, Envelope.make(Types.PAIR_CONFIRM_CODE, PairConfirmCode(code, 60), seq++, re = req.id))
                send(webSocket, Envelope.make(Types.PAIR_OK, PairOk(qr.macId, "Mac", 47321), seq++, re = req.id))
                webSocket.close(4000, "paired")
            }
        }).build())

        val events = Collections.synchronizedList(mutableListOf<ClientEvent>())
        val phone = PhoneInfo(UUID.randomUUID(), "Pixel", "Google", "Pixel", "17", 37, "1")
        val conn = WsConnection(OkHttpClient(), server.url("/v1").toString(), SessionClient.pairing(qr, phone, signer), scope) {
            events += it
        }
        conn.open()
        val end = withTimeout(5_000) { conn.ended.await() }

        assertTrue(events.contains(ClientEvent.ShowCode(code)), "events: $events")
        assertTrue(events.contains(ClientEvent.Paired(qr.macId, "Mac", 47321)), "pair.ok must not be lost to the close: $events")
        assertEquals(4000, (end as ConnectionEnd.Closed).code)
    }
}
