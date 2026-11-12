package dev.phonebridge.transport

import dev.phonebridge.protocol.EnvelopeCodec
import dev.phonebridge.security.PinnedTls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Why a connection ended. */
sealed interface ConnectionEnd {
    data class Closed(val code: Int, val reason: String) : ConnectionEnd
    data class Failed(val error: Throwable) : ConnectionEnd {
        /** TLS rejected: our key isn't trusted by the Mac, or the Mac's key doesn't match the pin. */
        val isTlsRejection get() = error is SSLException
    }
}

/**
 * One WebSocket-over-mutual-TLS connection driven by a [SessionClient]. All session work runs on a single
 * serial dispatcher, so the state machine never sees concurrent calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WsConnection internal constructor(
    private val http: OkHttpClient,
    private val url: String,
    private val client: SessionClient,
    private val scope: CoroutineScope,
    private val onEvent: suspend (ClientEvent) -> Unit,
) {
    constructor(
        host: String,
        port: Int,
        tls: PinnedTls,
        client: SessionClient,
        scope: CoroutineScope,
        onEvent: suspend (ClientEvent) -> Unit,
    ) : this(pinnedClient(tls), "https://$host:$port/v1", client, scope, onEvent)

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private var socket: WebSocket? = null
    private var ticker: Job? = null
    val ended = CompletableDeferred<ConnectionEnd>()

    fun open() {
        val req = Request.Builder().url(url).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ticker = scope.launch(serial) {
                    while (isActive) { delay(1000); perform(client.tick()) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(serial) { perform(client.receive(text)) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // File chunks arrive in Phase 5.
            }

            // Close/failure go through the same serial queue as messages, so a message that arrived before the
            // close (e.g. pair.ok followed by close 4000) is always processed first.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                scope.launch(serial) { finish(ConnectionEnd.Closed(code, reason)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch(serial) { finish(ConnectionEnd.Closed(code, reason)) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch(serial) { finish(ConnectionEnd.Failed(t)) }
            }
        })
    }

    /** Run actions produced outside a frame callback (e.g. a feature sending a message). */
    fun submit(actions: () -> List<ClientAction>) {
        scope.launch(serial) { perform(actions()) }
    }

    fun close(code: Int = 1000, reason: String = "bye") {
        socket?.close(code, reason)
        finish(ConnectionEnd.Closed(code, reason))
    }

    private suspend fun perform(actions: List<ClientAction>) {
        for (a in actions) when (a) {
            is ClientAction.Send -> socket?.send(EnvelopeCodec.encode(a.envelope))
            is ClientAction.Close -> { socket?.close(a.code.coerceIn(1000, 4999), a.reason.take(120)); finish(ConnectionEnd.Closed(a.code, a.reason)) }
            is ClientAction.Emit -> onEvent(a.event)
        }
    }

    companion object {
        fun pinnedClient(tls: PinnedTls): OkHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            .hostnameVerifier { _, _ -> true } // identity is the pinned key, not a hostname
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // app-level heartbeat handles liveness
            .build()
    }

    private fun finish(end: ConnectionEnd) {
        ticker?.cancel()
        ended.complete(end)
    }
}
