package dev.phonebridge.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.UUID

/**
 * Loads the shared fixtures in protocol/fixtures (generated from PROTOCOL.md) and proves this implementation
 * decodes and re-encodes them exactly. The mac test suite (PBProtocolTests) runs the same fixtures.
 */
object Fixtures {
    val root: File = File(requireNotNull(System.getProperty("pb.fixtures")) { "pb.fixtures not set" })
    fun file(rel: String) = File(root, rel)
    fun text(rel: String) = file(rel).readText()

    @JvmStatic
    fun messageFixtures(): List<String> = root.walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .filterNot { it.startsWith("invalid/") || it.startsWith("frames/") || it.startsWith("crypto/") }
        .sorted()
        .toList()
}

class ConformanceTest {
    @Test
    fun `fixture directory is found and non-empty`() {
        assertTrue(Fixtures.messageFixtures().size >= 35, "fixtures missing — run protocol/tools/extract_fixtures.py")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.phonebridge.protocol.Fixtures#messageFixtures")
    fun `every message fixture decodes, validates and re-encodes identically`(rel: String) {
        val raw = Fixtures.text(rel)
        val env = EnvelopeCodec.decode(raw)
        val mt = Types.byType[env.type] ?: error("unknown type ${env.type} in $rel")

        fun <P : MessagePayload> rebuild(t: MessageType<P>): Envelope =
            Envelope.make(t, env.decode(t), seq = env.seq, re = env.re, id = env.id, ts = env.ts)
        val rebuilt = rebuild(mt)

        val known = setOf("v", "type", "id", "seq", "re", "ts", "payload")
        val expected = JsonObject(PBJson.parseToJsonElement(raw).jsonObject.filterKeys { it in known })
        val actual = PBJson.parseToJsonElement(EnvelopeCodec.encode(rebuilt))
        assertEquals(expected, actual, "$rel did not round-trip")
    }

    @Test
    fun `every registered message type has at least one fixture`() {
        val seen = Fixtures.messageFixtures().map { EnvelopeCodec.decode(Fixtures.text(it)).type }.toSet()
        val missing = Types.byType.keys - seen
        assertTrue(missing.isEmpty(), "no fixture for: ${missing.sorted()}")
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("invalidCases")
    fun `invalid fixtures are rejected with the spec's error code`(rel: String, code: ErrorCode) {
        val e = assertThrows<ProtocolException> { EnvelopeCodec.decode(Fixtures.text(rel)) }
        assertEquals(code, e.code)
    }

    @Test
    fun `garbage and oversize input is rejected`() {
        assertThrows<ProtocolException> { EnvelopeCodec.decode("not json") }
        assertThrows<ProtocolException> { EnvelopeCodec.decode("[]") }
        val e = assertThrows<ProtocolException> { EnvelopeCodec.decode(" ".repeat(PBConstants.MAX_TEXT_FRAME_BYTES + 1)) }
        assertEquals(ErrorCode.FRAME_TOO_LARGE, e.code)
    }

    companion object {
        @JvmStatic
        fun invalidCases() = listOf(
            Arguments.of("invalid/missing_type.json", ErrorCode.INVALID_PAYLOAD),
            Arguments.of("invalid/wrong_version.json", ErrorCode.VERSION_MISMATCH),
            Arguments.of("invalid/bad_id.json", ErrorCode.INVALID_PAYLOAD),
        )
    }
}

class FrameTest {
    @ParameterizedTest
    @ValueSource(strings = ["frames/chunk_basic", "frames/chunk_offset"])
    fun `decode and re-encode`(name: String) {
        val bin = Fixtures.file("$name.bin").readBytes()
        val exp = PBJson.parseToJsonElement(Fixtures.text("$name.json")).jsonObject
        val f = ChunkFrame.decode(bin)
        assertEquals(exp["transferId"]!!.jsonPrimitive.content, f.transferId.toString())
        assertEquals(exp["offset"]!!.jsonPrimitive.long, f.offset)
        assertEquals(exp["length"]!!.jsonPrimitive.int, f.payload.size)
        assertEquals(exp["payloadHex"]!!.jsonPrimitive.content, Hex.encode(f.payload))
        assertEquals(exp["crc32"]!!.jsonPrimitive.long, ChunkFrame.crc32(f.payload))
        assertArrayEquals(bin, f.encode(), "re-encoding must be byte-identical")
    }

    @Test
    fun `corrupted payload reports CrcMismatch with the header offset`() {
        val bin = Fixtures.file("frames/chunk_offset.bin").readBytes()
        bin[bin.size - 1] = (bin[bin.size - 1].toInt() xor 0xFF).toByte()
        val e = assertThrows<ChunkFrame.DecodeError.CrcMismatch> { ChunkFrame.decode(bin) }
        assertEquals(UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100"), e.transferId)
        assertEquals(262_144L, e.offset)
    }

    @Test
    fun `malformed headers are rejected`() {
        val good = Fixtures.file("frames/chunk_basic.bin").readBytes()
        assertThrows<ChunkFrame.DecodeError.TooShort> { ChunkFrame.decode(good.copyOf(10)) }
        assertThrows<ChunkFrame.DecodeError.BadMagic> { ChunkFrame.decode(good.copyOf().also { it[0] = 0 }) }
        val v = assertThrows<ChunkFrame.DecodeError.UnsupportedVersion> { ChunkFrame.decode(good.copyOf().also { it[2] = 9 }) }
        assertEquals(9, v.version)
        assertThrows<ChunkFrame.DecodeError.LengthMismatch> { ChunkFrame.decode(good + byteArrayOf(1)) }
    }
}

class CryptoVectorTest {
    private val v = PBJson.parseToJsonElement(Fixtures.text("crypto/vectors.json")).jsonObject
    private fun s(section: String, key: String) = v[section]!!.jsonObject[key]!!.jsonPrimitive.content

    @Test
    fun sasCode() {
        val code = ProtocolCrypto.sasCode(
            Hex.decode(s("sas", "macSpkiFp")), Hex.decode(s("sas", "phoneSpkiFp")), Hex.decode(s("sas", "macNonceHex")),
        )
        assertEquals(s("sas", "code"), code)
    }

    @Test
    fun authSigningBytes() {
        val bytes = ProtocolCrypto.authSigningBytes(
            Hex.decode(s("authSigningBytes", "macNonceHex")),
            Hex.decode(s("authSigningBytes", "clientNonceHex")),
            UUID.fromString(s("authSigningBytes", "macDeviceId")),
            UUID.fromString(s("authSigningBytes", "phoneDeviceId")),
        )
        assertEquals(s("authSigningBytes", "bytesHex"), Hex.encode(bytes))
    }

    @Test
    fun tokenHash() {
        val token = B64.decode(s("tokenHash", "tokenB64url"))!!
        assertEquals(32, token.size)
        assertEquals(s("tokenHash", "sha256"), Hex.encode(ProtocolCrypto.tokenHash(token)))
    }

    @Test
    fun constantTimeEqual() {
        assertTrue(ProtocolCrypto.constantTimeEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ProtocolCrypto.constantTimeEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(ProtocolCrypto.constantTimeEqual(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}

class ValidationTest {
    private val phone = UUID.fromString("a1b2c3d4-e5f6-4789-8abc-def012345678")

    @Test
    fun `sequence rule rejects replays and gaps`() {
        val s = InboundSequence()
        s.accept(1); s.accept(2)
        assertEquals(ErrorCode.SEQUENCE_VIOLATION, assertThrows<ProtocolException> { s.accept(2) }.code)
        assertThrows<ProtocolException> { s.accept(5) }
        val o = OutboundSequence()
        assertEquals(1, o.take()); assertEquals(2, o.take())
    }

    @Test
    fun `ring duration must be an allowed value`() {
        assertThrows<ProtocolException> { Envelope.make(Types.PHONE_RING, PhoneRing(45), 1).decode(Types.PHONE_RING) }
        Envelope.make(Types.PHONE_RING, PhoneRing(60), 1).decode(Types.PHONE_RING)
    }

    @Test
    fun `clipboard hash is verified`() {
        val ok = ClipboardSet.of(phone, "hello")
        Envelope.make(Types.CLIPBOARD_SET, ok, 1).decode(Types.CLIPBOARD_SET)
        val tampered = ok.copy(text = "tampered")
        assertThrows<ProtocolException> { Envelope.make(Types.CLIPBOARD_SET, tampered, 1).decode(Types.CLIPBOARD_SET) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["../etc/passwd", "a/b", "..", "", "x\u0000y"])
    fun `unsafe file names are rejected`(name: String) {
        val o = FileOffer(UUID.randomUUID(), name, 1, "text/plain", "a".repeat(64))
        assertThrows<ProtocolException> { Envelope.make(Types.FILE_OFFER, o, 1).decode(Types.FILE_OFFER) }
    }

    @Test
    fun `base64url is strict`() {
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), B64.decode("AAECAw"))
        assertNull(B64.decode("AAECAw==")) // padding not allowed
        assertNull(B64.decode("AA+/"))     // standard alphabet not allowed
        assertEquals("-_8", B64.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    @Test
    fun `decoding with the wrong type fails`() {
        val env = Envelope.make(Types.PING, Ping, 1)
        assertThrows<ProtocolException> { env.decode(Types.PONG) }
    }

    @Test
    fun `non-canonical uuids are rejected`() {
        assertNull(Uuids.parse("1-1-1-1-1"))
        assertEquals(UUID.fromString("3f8e1c2a-0b4d-4e6f-8a9b-1c2d3e4f5a6b"), Uuids.parse("3F8E1C2A-0B4D-4E6F-8A9B-1C2D3E4F5A6B"))
    }

    @Test
    fun `uuid bytes round-trip in RFC 4122 order`() {
        val u = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")
        assertEquals("0f0e0d0c0b0a49088706050403020100", Hex.encode(Uuids.toBytes(u)))
        assertEquals(u, Uuids.fromBytes(Uuids.toBytes(u)))
    }

    @Test
    fun `unknown enum values decode to UNKNOWN`() {
        val json = """{"level":50,"charging":false,"plugged":"solar"}"""
        assertEquals(Plugged.UNKNOWN, PBJson.decodeFromString(StatusBattery.serializer(), json).plugged)
    }

    @Test
    fun `error codes map to close codes`() {
        assertEquals(4001, ErrorCode.AUTH_FAILED.closeCode)
        assertTrue(ErrorCode.AUTH_FAILED.isFatal)
        assertFalse(ErrorCode.PAIRED.isFatal)
        assertFalse(ErrorCode.UNSUPPORTED_TYPE.isFatal)
        assertEquals(ErrorCode.REVOKED, ErrorCode.fromCloseCode(4003))
    }
}
