package dev.phonebridge.transport

import dev.phonebridge.protocol.B64
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.EnvelopeCodec
import dev.phonebridge.protocol.ErrorCode
import dev.phonebridge.protocol.ErrorPayload
import dev.phonebridge.protocol.HelloMode
import dev.phonebridge.protocol.Hex
import dev.phonebridge.protocol.PairConfirmCode
import dev.phonebridge.protocol.PairDenied
import dev.phonebridge.protocol.PairDeniedReason
import dev.phonebridge.protocol.PairOk
import dev.phonebridge.protocol.PairRevoked
import dev.phonebridge.protocol.Ping
import dev.phonebridge.protocol.ProtocolCrypto
import dev.phonebridge.protocol.Ready
import dev.phonebridge.protocol.Types
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Test signer backed by a software P-256 key (stands in for the Keystore). */
class SoftSigner : Signer {
    val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val fingerprint: ByteArray = ProtocolCrypto.spkiFingerprint(keyPair.public.encoded)
    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private); update(message); sign()
    }
    fun verify(message: ByteArray, sig: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
        initVerify(keyPair.public); update(message); verify(sig)
    }
}

private val PHONE = PhoneInfo(UUID.randomUUID(), "Pixel 9", "Google", "Pixel 9", "17", 37, "1.0.0")

private fun List<ClientAction>.sent() = filterIsInstance<ClientAction.Send>().map { it.envelope }
private fun List<ClientAction>.events() = filterIsInstance<ClientAction.Emit>().map { it.event }
private fun List<ClientAction>.closeCode() = filterIsInstance<ClientAction.Close>().firstOrNull()?.code
private fun text(e: Envelope) = EnvelopeCodec.encode(e)

class SessionModeTest {
    private val macId = UUID.randomUUID()
    private val nonce = ProtocolCrypto.randomBytes(32)
    private val signer = SoftSigner()
    private var clock = 1_000_000L
    private val client = SessionClient.session(macId, PHONE, signer, now = { clock })

    @Test
    fun `answers hello with a correctly signed auth`() {
        val out = client.receive(text(SessionClient.hello(HelloMode.SESSION, macId, nonce)))
        val env = out.sent().single()
        assertEquals(Types.AUTH.type, env.type)
        val auth = env.decode(Types.AUTH)
        val msg = ProtocolCrypto.authSigningBytes(nonce, auth.clientNonce.bytes, macId, PHONE.deviceId)
        assertTrue(signer.verify(msg, auth.sig.bytes), "Mac must be able to verify the signature")
        assertEquals(PHONE.deviceId, auth.deviceId)
    }

    @Test
    fun `ready completes the handshake`() {
        client.receive(text(SessionClient.hello(HelloMode.SESSION, macId, nonce)))
        val out = client.receive(text(Envelope.make(Types.READY, Ready(UUID.randomUUID(), listOf("clipboard")), seq = 2)))
        val ready = out.events().single() as ClientEvent.Ready
        assertEquals(listOf("clipboard"), ready.enabled)
        assertTrue(client.isReady)
    }

    @Test
    fun `hello from a different Mac is rejected`() {
        val out = client.receive(text(SessionClient.hello(HelloMode.SESSION, UUID.randomUUID(), nonce)))
        assertEquals(ErrorCode.AUTH_FAILED.closeCode, out.closeCode())
        assertTrue(out.sent().none { it.type == Types.AUTH.type }, "never sign for an unexpected Mac")
    }

    @Test
    fun `pairing-mode hello means we were removed`() {
        val out = client.receive(text(SessionClient.hello(HelloMode.PAIRING, macId, nonce)))
        assertEquals(ClientEvent.Revoked, out.events().single())
        assertTrue(out.sent().isEmpty())
    }

    @Test
    fun `fatal revoked error stops reconnecting`() {
        val err = Envelope.make(Types.ERROR, ErrorPayload(ErrorCode.REVOKED, "unknown device", true), seq = 1)
        assertEquals(ClientEvent.Revoked, client.receive(text(err)).events().single())
    }

    @Test
    fun `fatal auth error is reported`() {
        client.receive(text(SessionClient.hello(HelloMode.SESSION, macId, nonce)))
        val err = Envelope.make(Types.ERROR, ErrorPayload(ErrorCode.AUTH_FAILED, "bad sig", true), seq = 2)
        val e = client.receive(text(err)).events().single() as ClientEvent.Rejected
        assertEquals(ErrorCode.AUTH_FAILED, e.code)
    }

    @Test
    fun `pair revoked while connected`() {
        handshake()
        val out = client.receive(text(Envelope.make(Types.PAIR_REVOKED, PairRevoked("removed_by_user"), seq = 3)))
        assertEquals(ClientEvent.Revoked, out.events().single())
    }

    @Test
    fun `replayed frame from the Mac is a sequence violation`() {
        val hello = text(SessionClient.hello(HelloMode.SESSION, macId, nonce))
        client.receive(hello)
        assertEquals(ErrorCode.SEQUENCE_VIOLATION.closeCode, client.receive(hello).closeCode())
    }

    @Test
    fun `heartbeat pings every 15s and detects a dead Mac`() {
        handshake()
        clock += 15_000
        assertEquals(Types.PING.type, client.tick().sent().single().type)
        clock += 10_000
        assertTrue(client.tick().isEmpty())
        clock += 36_000 // > 45 s since last inbound
        assertEquals(ErrorCode.HEARTBEAT_TIMEOUT.closeCode, client.tick().closeCode())
    }

    @Test
    fun `answers pings`() {
        handshake()
        val ping = Envelope.make(Types.PING, Ping, seq = 3)
        val pong = client.receive(text(ping)).sent().single()
        assertEquals(Types.PONG.type, pong.type)
        assertEquals(ping.id, pong.re)
    }

    @Test
    fun `handshake timeout`() {
        clock += 16_000
        assertEquals(ErrorCode.HEARTBEAT_TIMEOUT.closeCode, client.tick().closeCode())
    }

    private fun handshake() {
        client.receive(text(SessionClient.hello(HelloMode.SESSION, macId, nonce)))
        client.receive(text(Envelope.make(Types.READY, Ready(UUID.randomUUID(), emptyList()), seq = 2)))
    }
}

class PairingModeTest {
    private val signer = SoftSigner()
    private val macFp = ProtocolCrypto.randomBytes(32)
    private val nonce = ProtocolCrypto.randomBytes(32)
    private val qr = PairingQr("192.168.1.4", 47321, Hex.encode(macFp), ProtocolCrypto.randomBytes(32), UUID.randomUUID(), "My Mac")
    private val client = SessionClient.pairing(qr, PHONE, signer)
    private val code = ProtocolCrypto.sasCode(macFp, signer.fingerprint, nonce)

    private fun hello() = client.receive(text(SessionClient.hello(HelloMode.PAIRING, qr.macId, nonce)))

    @Test
    fun `sends the token from the QR code`() {
        val req = hello().sent().single().decode(Types.PAIR_REQUEST)
        assertArrayEquals(qr.token, req.token.bytes)
        assertEquals(PHONE.deviceId, req.deviceId)
    }

    @Test
    fun `matching code is shown, then pair ok completes`() {
        val reqId = hello().sent().single().id
        val show = client.receive(text(Envelope.make(Types.PAIR_CONFIRM_CODE, PairConfirmCode(code, 60), seq = 2, re = reqId)))
        assertEquals(ClientEvent.ShowCode(code), show.events().single())
        val ok = client.receive(text(Envelope.make(Types.PAIR_OK, PairOk(qr.macId, "My Mac", 47321), seq = 3, re = reqId)))
        assertEquals(ClientEvent.Paired(qr.macId, "My Mac", 47321), ok.events().single())
    }

    @Test
    fun `mismatched code aborts (someone in the middle)`() {
        hello()
        val wrong = if (code == "000000") "000001" else "000000"
        val out = client.receive(text(Envelope.make(Types.PAIR_CONFIRM_CODE, PairConfirmCode(wrong, 60), seq = 2)))
        assertEquals(ClientEvent.PairingFailed("code_mismatch"), out.events().single())
        assertNotNull(out.closeCode())
    }

    @Test
    fun `denied on the Mac`() {
        hello()
        client.receive(text(Envelope.make(Types.PAIR_CONFIRM_CODE, PairConfirmCode(code, 60), seq = 2)))
        val out = client.receive(text(Envelope.make(Types.PAIR_DENIED, PairDenied(PairDeniedReason.USER_DENIED), seq = 3)))
        assertEquals(ClientEvent.PairingFailed("user_denied"), out.events().single())
    }

    @Test
    fun `wrong Mac id in hello`() {
        val out = client.receive(text(SessionClient.hello(HelloMode.PAIRING, UUID.randomUUID(), nonce)))
        assertEquals(ClientEvent.PairingFailed("wrong_mac"), out.events().single())
        assertTrue(out.sent().isEmpty(), "token must not be sent to the wrong Mac")
    }

    @Test
    fun `token error from the Mac`() {
        hello()
        val err = Envelope.make(Types.ERROR, ErrorPayload(ErrorCode.TOKEN_EXPIRED, "used", true), seq = 2)
        assertEquals(ClientEvent.PairingFailed("token_expired"), client.receive(text(err)).events().single())
    }
}

class PairingQrTest {
    private val fp = "a".repeat(64)
    private val token = B64.encode(ByteArray(32) { it.toByte() })
    private val id = "6f1c2a9e-3b7d-4e5f-9a10-2b3c4d5e6f70"

    @Test
    fun `parses the Mac's QR format`() {
        val qr = PairingQr.parse("phonebridge://pair?v=1&h=192.168.1.4&p=47321&fp=$fp&t=$token&id=$id&n=My%20MacBook%20Air")
        assertNotNull(qr)
        assertEquals("192.168.1.4", qr!!.host)
        assertEquals(47321, qr.port)
        assertEquals("My MacBook Air", qr.macName)
        assertEquals(32, qr.token.size)
        assertTrue(!qr.toString().contains(token), "token must never appear in logs")
    }

    @Test
    fun `rejects anything malformed`() {
        val good = "phonebridge://pair?v=1&h=192.168.1.4&p=47321&fp=$fp&t=$token&id=$id&n=Mac"
        val bad = listOf(
            "https://example.com",
            good.replace("v=1", "v=2"),
            good.replace("h=192.168.1.4", "h=evil.example.com"),
            good.replace("p=47321", "p=70000"),
            good.replace("fp=$fp", "fp=abc"),
            good.replace("t=$token", "t=short"),
            good.replace("id=$id", "id=nope"),
            "not a url at all",
        )
        for (s in bad) assertNull(PairingQr.parse(s), s)
    }
}

class BackoffTest {
    @Test
    fun `grows to 30s with jitter`() {
        val r = kotlin.random.Random(1)
        val values = (1..10).map { ConnectionManager.backoffMs(it, r) }
        assertTrue(values[0] in 800..1200)
        assertTrue(values[1] in 1600..2400)
        assertTrue(values.last() in 24_000..36_000)
    }
}
