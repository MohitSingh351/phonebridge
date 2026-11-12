package dev.phonebridge.files

import dev.phonebridge.protocol.ChunkFrame
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.EnvelopeCodec
import dev.phonebridge.protocol.FileOffer
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.protocol.OutboundSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import kotlin.random.Random

// ---------------------------------------------------------------- in-memory platform

/** Deterministic content of any size without holding it in memory: byte i = f(seed, i). */
open class GeneratedSource(override val size: Long, private val seed: Int) : FileSource {
    var reads = 0
    var corruptAt: Long = -1 // simulate the file changing on disk after hashing
    override open fun read(offset: Long, max: Int): ByteArray {
        reads++
        return ByteArray(minOf(max.toLong(), size - offset).toInt()) { i ->
            val pos = offset + i
            if (pos == corruptAt) 0x55 else ((pos * 31 + seed) xor (pos ushr 8)).toByte()
        }
    }
    override fun close() {}
    fun sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        var off = 0L
        val saved = corruptAt; corruptAt = -1
        while (off < size) { val b = read(off, 1 shl 20); md.update(b); off += b.size }
        corruptAt = saved; reads = 0
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Sink that keeps only a running hash + durable length, so huge tests stay memory-flat. */
class HashingSink(override val ref: String, private val files: MemoryIO) : FileSink {
    var length = 0L
    var durable = 0L
    var bytes = java.io.ByteArrayOutputStream() // kept for files <= 64 MiB so a full rehash is possible after resume
    override fun write(offset: Long, data: ByteArray) {
        check(offset == length) { "non-sequential write at $offset (len $length)" }
        if (length < 64 shl 20) bytes.write(data)
        length += data.size
    }
    override fun sync() { durable = length }
    override fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray().copyOf(length.toInt()))
        .joinToString("") { "%02x".format(it) }
    override fun commit(): String = files.commit(this)
    override fun discard() { files.sinks.remove(ref); files.discarded += ref }
    override fun close() {}
    /** Simulates a crash: unsynced data is lost. */
    fun crash() { length = durable; val keep = bytes.toByteArray().copyOf(durable.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()); bytes = java.io.ByteArrayOutputStream().apply { write(keep) } }
}

class MemoryIO(var freeBytes: Long = Long.MAX_VALUE) : FileIO {
    val sources = HashMap<String, GeneratedSource>()
    val sinks = HashMap<String, HashingSink>()
    val saved = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    private var n = 0
    override fun openSource(ref: String) = sources[ref]
    override fun createSink(offer: FileOffer): FileSink {
        if (offer.size > freeBytes) throw FileIO.InsufficientSpace()
        return HashingSink("sink-${n++}-${offer.name}", this).also { sinks[it.ref] = it }
    }
    override fun reopenSink(ref: String, name: String, offset: Long): FileSink? = sinks[ref]?.also { s ->
        if (s.length > offset) { s.crash(); s.length = offset }
    }
    fun commit(s: HashingSink): String {
        val base = s.ref.substringAfter('-').substringAfter('-')
        var name = base; var i = 1
        while (name in saved) { name = base.substringBeforeLast('.') + " ($i)." + base.substringAfterLast('.'); i++ }
        saved += name
        return name
    }
}

class MemoryStore : TransferStore {
    var records: List<TransferRecord> = emptyList()
    override fun load() = records
    override fun save(records: List<TransferRecord>) { this.records = records }
}

/**
 * Two engines joined by an in-memory "network". Frames queue up and are delivered by [deliver], so tests
 * can drop, corrupt, reorder, or cut the link mid-flight — exactly the failures the protocol must survive.
 */
class Link(val clock: () -> Long) {
    inner class Side(val name: String, val io: MemoryIO = MemoryIO(), val store: MemoryStore = MemoryStore()) {
        var engine = TransferEngine(io, store, clock)
        val seq = OutboundSequence()
        lateinit var peer: Side
        val wire = object : TransferWire {
            override fun <P : MessagePayload> json(mt: MessageType<P>, payload: P) {
                queue += Frame(peer, EnvelopeCodec.encode(Envelope.make(mt, payload, seq.take())), null)
            }
            override fun binary(frame: ByteArray) { queue += Frame(peer, null, frame) }
        }
        fun restart() { engine = TransferEngine(io, store, clock) }
    }
    data class Frame(val to: Side, val json: String?, val bin: ByteArray?)

    val queue = ArrayDeque<Frame>()
    val phone = Side("phone")
    val mac = Side("mac")
    var maxBinaryInFlight = 0
    var connected = false
    /** Called for each frame before delivery; return null to drop it, or a replacement. */
    var filter: (Frame) -> Frame? = { it }

    init { phone.peer = mac; mac.peer = phone }

    fun connect() {
        connected = true
        mac.engine.onSessionStarted(mac.wire)
        phone.engine.onSessionStarted(phone.wire)
    }

    fun disconnect() {
        connected = false
        queue.clear() // in-flight frames are lost
        phone.engine.onSessionEnded(); mac.engine.onSessionEnded()
    }

    /** Deliver frames until the network is idle (or [maxFrames] were delivered). */
    fun deliver(maxFrames: Int = Int.MAX_VALUE): Int {
        var n = 0
        while (connected && queue.isNotEmpty() && n < maxFrames) {
            maxBinaryInFlight = maxOf(maxBinaryInFlight, queue.count { it.bin != null })
            val f = filter(queue.removeFirst()) ?: continue
            n++
            if (f.json != null) f.to.engine.onMessage(EnvelopeCodec.decode(f.json)) else f.to.engine.onBinary(f.bin!!)
        }
        return n
    }
}

class TransferEngineTest {
    private var now = 1_000_000L
    private val link = Link { now }
    private val phone = link.phone
    private val mac = link.mac

    private fun offer(size: Long, name: String = "file.bin", seed: Int = 7): Pair<String, GeneratedSource> {
        val src = GeneratedSource(size, seed)
        val ref = "src:$name:$seed:${phone.io.sources.size}" // unique per offer, like real content URIs
        phone.io.sources[ref] = src
        val id = phone.engine.send(ref, name, size, "application/octet-stream", src.sha256())
        return id to src
    }

    private fun state(side: Link.Side, id: String) = side.engine.get(id)!!.state

    @ParameterizedTest
    @ValueSource(longs = [0, 1, 11, 262_143, 262_144, 262_145, 3_000_000])
    fun `transfers complete and verify for edge sizes`(size: Long) {
        link.connect()
        val (id, _) = offer(size)
        link.deliver()
        assertEquals(TransferState.DONE, state(phone, id))
        assertEquals(TransferState.DONE, state(mac, id))
        assertEquals("file.bin", mac.engine.get(id)!!.savedName)
        assertEquals("file.bin", phone.engine.get(id)!!.savedName)
    }

    @Test
    fun `offer made while offline is sent when the session starts`() {
        val (id, _) = offer(500_000)
        assertEquals(TransferState.WAITING, state(phone, id))
        link.connect(); link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
    }

    @Test
    fun `large transfer streams with bounded memory (window of 8 chunks)`() {
        link.connect()
        val size = 256L shl 20 // 256 MiB, generated on the fly; nothing is held in memory
        val (id, _) = offer(size)
        link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
        assertTrue(link.maxBinaryInFlight <= 8, "never more than 8 chunks in flight, was ${link.maxBinaryInFlight}")
    }

    @ParameterizedTest
    @ValueSource(ints = [10, 50, 99])
    fun `connection cut mid-transfer resumes where it stopped`(percent: Int) {
        val size = 40L shl 20
        link.connect()
        val (id, src) = offer(size)
        // Deliver until the receiver passes the cut point, then pull the plug.
        while ((mac.engine.get(id)?.transferred ?: 0) < size * percent / 100) link.deliver(1)
        link.disconnect()
        assertEquals(TransferState.WAITING, state(phone, id))
        val resumeFrom = mac.engine.get(id)!!.offset
        assertTrue(resumeFrom > 0 || percent < 20, "should keep durable progress")
        val readsBefore = src.reads
        link.connect(); link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
        val reread = (src.reads - readsBefore).toLong() * 262_144
        assertTrue(reread <= size - resumeFrom + 9L * 262_144, "must not resend from zero")
    }

    @Test
    fun `half-open drop - receiver never notices, sender reconnects - resumes, not from zero`() {
        // Real-device case: the phone's Wi-Fi drops; the Mac's old session lingers until its heartbeat
        // timeout, so the Mac engine never gets onSessionEnded before the phone's new session re-offers.
        val size = 40L shl 20
        link.connect()
        val (id, _) = offer(size)
        while ((mac.engine.get(id)?.transferred ?: 0) < 20L shl 20) link.deliver(1)
        link.queue.clear()
        phone.engine.onSessionEnded()          // only the phone sees the drop
        mac.engine.onSessionStarted(mac.wire)  // new session on the Mac side (old one still "alive")
        phone.engine.onSessionStarted(phone.wire)
        link.deliver(3)                        // offer -> accept
        val resumeAt = phone.engine.get(id)!!.offset
        assertTrue(resumeAt >= 16L shl 20, "should resume near 20 MiB, resumed at $resumeAt")
        link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
    }

    @Test
    fun `receiver app restart resumes from the last durable offset`() {
        val size = 30L shl 20
        link.connect()
        val (id, _) = offer(size)
        while ((mac.engine.get(id)?.transferred ?: 0) < 20L shl 20) link.deliver(1)
        link.disconnect()
        mac.io.sinks.values.single().crash() // unsynced tail is lost, like a real crash
        mac.restart()
        val durable = mac.engine.get(id)!!.offset
        assertTrue(durable >= 16L shl 20, "4 MiB sync cadence keeps most progress, got $durable")
        link.connect(); link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
        assertEquals(TransferState.DONE, state(phone, id))
    }

    @Test
    fun `sender app restart resumes too`() {
        val size = 12L shl 20
        link.connect()
        val (id, _) = offer(size)
        while ((mac.engine.get(id)?.transferred ?: 0) < 6L shl 20) link.deliver(1)
        link.disconnect()
        phone.restart()
        assertEquals(TransferState.WAITING, state(phone, id))
        link.connect(); link.deliver()
        assertEquals(TransferState.DONE, state(phone, id))
    }

    @Test
    fun `corrupted chunk is detected and re-sent`() {
        var corrupted = 0
        link.filter = { f ->
            if (f.bin != null && corrupted == 0 && ChunkFrame.decode(f.bin).offset == 2L * 262_144) {
                corrupted++
                f.copy(bin = f.bin.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() })
            } else f
        }
        link.connect()
        val (id, _) = offer(3L shl 20)
        link.deliver()
        repeat(15) { now += 1000; phone.engine.tick(); link.deliver() }
        assertEquals(1, corrupted)
        assertEquals(TransferState.DONE, state(mac, id))
    }

    @Test
    fun `dropped chunk is recovered`() {
        var dropped = false
        link.filter = { f ->
            if (!dropped && f.bin != null && ChunkFrame.decode(f.bin).offset == 262_144L) { dropped = true; null } else f
        }
        link.connect()
        val (id, _) = offer(2L shl 20)
        link.deliver()
        repeat(20) { now += 1000; phone.engine.tick(); link.deliver() }
        assertTrue(dropped)
        assertEquals(TransferState.DONE, state(mac, id))
    }

    @Test
    fun `stalls retry with backoff, then fail, and can be retried`() {
        link.connect()
        val (id, _) = offer(2L shl 20)
        link.filter = { f -> if (f.bin != null) null else f } // black-hole all data
        link.deliver()
        repeat(120) { now += 1000; phone.engine.tick(); link.deliver() }
        assertEquals(TransferState.FAILED, state(phone, id))
        assertEquals("transfer_failed", phone.engine.get(id)!!.error)
        link.filter = { it }
        phone.engine.retry(id)
        link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
        assertEquals(TransferState.DONE, state(phone, id))
    }

    @Test
    fun `pause and resume from the sender`() {
        link.connect()
        val (id, _) = offer(8L shl 20)
        link.deliver(10)
        phone.engine.pause(id)
        link.deliver()
        assertEquals(TransferState.PAUSED, state(phone, id))
        assertEquals(TransferState.PAUSED, state(mac, id))
        val got = mac.engine.get(id)!!.transferred
        assertTrue(got < 8L shl 20)
        phone.engine.resume(id)
        link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
    }

    @Test
    fun `pause and resume from the receiver`() {
        link.connect()
        val (id, _) = offer(8L shl 20)
        link.deliver(10)
        mac.engine.pause(id)
        link.deliver()
        assertEquals(TransferState.PAUSED, state(mac, id))
        assertEquals(TransferState.PAUSED, state(phone, id))
        mac.engine.resume(id)
        link.deliver()
        assertEquals(TransferState.DONE, state(phone, id))
    }

    @Test
    fun `locally paused transfer stays paused across reconnects`() {
        link.connect()
        val (id, _) = offer(8L shl 20)
        link.deliver(10)
        phone.engine.pause(id); link.deliver()
        link.disconnect(); link.connect(); link.deliver()
        assertEquals(TransferState.PAUSED, state(phone, id))
        assertEquals(TransferState.PAUSED, state(mac, id))
    }

    @Test
    fun `cancel from either side cleans up the partial file`() {
        link.connect()
        val (a, _) = offer(8L shl 20, name = "a.bin")
        link.deliver(10)
        phone.engine.cancel(a); link.deliver()
        assertEquals(TransferState.CANCELLED, state(mac, a))
        assertEquals(1, mac.io.discarded.size)

        val (b, _) = offer(8L shl 20, name = "b.bin", seed = 9)
        link.deliver(10)
        mac.engine.cancel(b); link.deliver()
        assertEquals(TransferState.CANCELLED, state(phone, b))
        assertEquals(2, mac.io.discarded.size)
    }

    @Test
    fun `file changed after hashing fails verification and discards the partial`() {
        link.connect()
        val (id, src) = offer(1L shl 20)
        src.corruptAt = 12345
        link.deliver()
        assertEquals(TransferState.FAILED, state(phone, id))
        assertEquals("hash_mismatch", phone.engine.get(id)!!.error)
        assertEquals(TransferState.FAILED, state(mac, id))
        assertEquals(1, mac.io.discarded.size)
        // Fixing the file and retrying starts over and succeeds.
        src.corruptAt = -1
        phone.engine.retry(id); link.deliver()
        assertEquals(TransferState.DONE, state(mac, id))
    }

    @Test
    fun `source that starts failing mid-transfer fails cleanly instead of crashing`() {
        // Real-device bug: a closed file descriptor threw from read() and crashed the app.
        link.connect()
        val (id, _) = offer(4L shl 20)
        link.deliver(5)
        link.disconnect()
        val broken = object : FileSource {
            override val size = 4L shl 20
            override fun read(offset: Long, max: Int): ByteArray = throw java.nio.channels.ClosedChannelException()
            override fun close() {}
        }
        val brokenIo = object : FileIO by phone.io { override fun openSource(ref: String) = broken }
        phone.engine = TransferEngine(brokenIo, phone.store, { now }) // sender restarts; source now unreadable
        link.connect(); link.deliver()
        assertEquals(TransferState.FAILED, state(phone, id))
        assertEquals("source_read_failed", phone.engine.get(id)!!.error)
        assertEquals(TransferState.CANCELLED, state(mac, id), "receiver is told and cleans up")
        assertEquals(1, mac.io.discarded.size)
    }

    @Test
    fun `no space on the receiver is reported`() {
        mac.io.freeBytes = 1000
        link.connect()
        val (id, _) = offer(5000)
        link.deliver()
        assertEquals(TransferState.FAILED, state(phone, id))
        assertEquals("insufficient_space", phone.engine.get(id)!!.error)
        assertNull(mac.engine.get(id))
    }

    @Test
    fun `duplicate names get unique saved names`() {
        link.connect()
        val (a, _) = offer(10, name = "photo.jpg")
        val (b, _) = offer(20, name = "photo.jpg", seed = 3)
        link.deliver()
        assertEquals("photo.jpg", mac.engine.get(a)!!.savedName)
        assertEquals("photo (1).jpg", mac.engine.get(b)!!.savedName)
    }

    @Test
    fun `history is capped and clearable`() {
        val small = Link { now }
        val eng = TransferEngine(small.mac.io, small.mac.store, { now }, historyLimit = 5)
        small.mac.engine = eng
        small.connect()
        repeat(8) { i ->
            val src = GeneratedSource(10, i)
            small.phone.io.sources["s$i"] = src
            small.phone.engine.send("s$i", "f$i.txt", 10, "text/plain", src.sha256())
            small.deliver(); now += 1
        }
        assertEquals(5, eng.all.size)
        eng.clearHistory()
        assertTrue(eng.all.isEmpty())
    }

    @Test
    fun `sender and receiver both survive a duplicate re-offer after completion`() {
        link.connect()
        val (id, _) = offer(100_000)
        link.deliver()
        // A late re-offer (e.g. result lost, then reconnect) must not re-download.
        link.disconnect()
        val rec = phone.engine.get(id)!!
        phone.store.records = phone.store.records.map { if (it.id == id) it.copy(state = TransferState.VERIFYING) else it }
        phone.restart()
        link.connect(); link.deliver()
        assertEquals(TransferState.DONE, state(phone, id))
        assertEquals(1, mac.io.saved.size)
        assertNotNull(rec)
    }

    @Test
    fun `random loss stress — always ends verified`() {
        val r = Random(3)
        link.filter = { f -> if (f.bin != null && r.nextInt(100) < 5) null else f } // 5 % chunk loss
        link.connect()
        val ids = (0 until 5).map { offer(r.nextLong(1, 3L shl 20), name = "f$it.bin", seed = it).first }
        repeat(200) { now += 1000; phone.engine.tick(); mac.engine.tick(); link.deliver() }
        for (id in ids) assertEquals(TransferState.DONE, state(mac, id), "transfer $id")
    }
}
