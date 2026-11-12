package dev.phonebridge.files

import dev.phonebridge.protocol.ChunkFrame
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.FileAccept
import dev.phonebridge.protocol.FileAck
import dev.phonebridge.protocol.FileCancel
import dev.phonebridge.protocol.FileDone
import dev.phonebridge.protocol.FileNack
import dev.phonebridge.protocol.FileOffer
import dev.phonebridge.protocol.FilePause
import dev.phonebridge.protocol.FileReject
import dev.phonebridge.protocol.FileRejectReason
import dev.phonebridge.protocol.FileResult
import dev.phonebridge.protocol.FileResultError
import dev.phonebridge.protocol.FileResume
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.protocol.PBConstants
import dev.phonebridge.protocol.ProtocolException
import dev.phonebridge.protocol.Types
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

// ---------------------------------------------------------------- platform seams (IO lives outside the engine)

/** A file being sent. Opened lazily; may become unavailable (e.g. share permission gone after restart). */
interface FileSource : AutoCloseable {
    val size: Long
    fun read(offset: Long, max: Int): ByteArray
}

/** Where an incoming file is written. Writes are positional so resume can continue at any offset. */
interface FileSink : AutoCloseable {
    /** Opaque reference persisted so the sink can be reopened after an app restart. */
    val ref: String
    fun write(offset: Long, data: ByteArray)
    /** Make everything written so far durable (fsync). */
    fun sync()
    /** SHA-256 of the whole partial file (used when the incremental hash isn't valid, e.g. after resume). */
    fun sha256(): String
    /** Finalise under a unique name (`photo (1).jpg` on collision); returns the saved name. */
    fun commit(): String
    /** Delete the partial file. */
    fun discard()
}

interface FileIO {
    fun openSource(ref: String): FileSource?
    /** Throws [InsufficientSpace] if there is no room. */
    fun createSink(offer: FileOffer): FileSink
    /** Reopen a partial file, truncated to [offset]. Null if it vanished. */
    fun reopenSink(ref: String, name: String, offset: Long): FileSink?
    class InsufficientSpace : Exception()
}

/** Sends on the live session. */
interface TransferWire {
    fun <P : MessagePayload> json(mt: MessageType<P>, payload: P)
    fun binary(frame: ByteArray)
}

/** Persists records (in-flight + history). */
interface TransferStore {
    fun load(): List<TransferRecord>
    fun save(records: List<TransferRecord>)
}

// ---------------------------------------------------------------- model

enum class Direction { OUT, IN }

enum class TransferState {
    /** Not yet accepted, or waiting for the connection to come back. */
    WAITING,
    ACTIVE,
    PAUSED,
    /** All bytes sent / received; hash being checked. */
    VERIFYING,
    DONE,
    FAILED,
    CANCELLED;

    val isFinal get() = this == DONE || this == FAILED || this == CANCELLED
}

enum class PausedBy { LOCAL, REMOTE }

@Serializable
data class TransferRecord(
    val id: String,
    val direction: Direction,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
    val state: TransferState,
    val pausedBy: PausedBy? = null,
    /** OUT: bytes acknowledged. IN: bytes durably written (safe resume point). */
    val offset: Long = 0,
    /** OUT: source reference (content URI / bookmark). IN: sink reference. */
    val ref: String? = null,
    val savedName: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
    /** Live progress (not persisted precisely; [offset] is the durable value). */
    val transferred: Long = offset,
    val bytesPerSec: Long = 0,
)

/**
 * The transfer state machine for one side (PROTOCOL.md §9). Symmetric: the same engine sends and
 * receives. Pure logic + small IO seams, NOT thread-safe: the caller runs every call on one serial
 * dispatcher. Mirrors mac `TransferEngine` — keep the two in step.
 */
class TransferEngine(
    private val io: FileIO,
    private val store: TransferStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val chunkSize: Int = PBConstants.MAX_CHUNK_PAYLOAD,
    private val window: Int = 8,
    private val stallMs: Long = 10_000,
    private val maxRetries: Int = 5,
    private val verifyTimeoutMs: Long = 120_000,
    private val syncEveryBytes: Long = 4L * 1024 * 1024,
    private val historyLimit: Int = 200,
    private val onChange: (List<TransferRecord>) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val records = LinkedHashMap<String, TransferRecord>()
    private var wire: TransferWire? = null

    // Runtime-only state per transfer.
    private class Out(var source: FileSource? = null, var next: Long = 0, var lastProgress: Long = 0,
                      var retries: Int = 0, var retryAt: Long = 0, var doneSentAt: Long = 0)
    private class In(var sink: FileSink? = null, var expected: Long = 0, var synced: Long = 0,
                     var hasher: MessageDigest? = null, var lastNack: Long = 0)
    private val outs = HashMap<String, Out>()
    private val ins = HashMap<String, In>()
    private val rate = HashMap<String, Pair<Long, Long>>() // id -> (timestamp, bytes) for speed

    init {
        // After a restart nothing is live: in-flight transfers wait for the connection (resume later).
        for (r in store.load()) {
            records[r.id] = if (!r.state.isFinal && r.state != TransferState.PAUSED) r.copy(state = TransferState.WAITING) else r
        }
    }

    val all: List<TransferRecord> get() = records.values.toList()
    fun get(id: String) = records[id]

    // ---------------------------------------------------------------- session

    fun onSessionStarted(wire: TransferWire) {
        log("session started")
        this.wire = wire
        // Re-offer every unfinished outgoing transfer; the receiver answers with its resume offset.
        for (r in records.values.toList()) {
            if (r.direction != Direction.OUT || r.state.isFinal) continue
            if (r.state == TransferState.PAUSED && r.pausedBy == PausedBy.LOCAL) continue
            sendOffer(r)
        }
    }

    fun onSessionEnded() {
        log("session ended")
        wire = null
        for (r in records.values.toList()) {
            if (r.state == TransferState.ACTIVE || r.state == TransferState.VERIFYING) update(r.copy(state = TransferState.WAITING))
            if (r.direction == Direction.IN) ins[r.id]?.let { syncIn(r.id, it) }
        }
        persist()
    }

    // ---------------------------------------------------------------- user actions

    /** Queue a file to send. The caller computed size + SHA-256 off this thread. */
    fun send(sourceRef: String, name: String, size: Long, mime: String, sha256: String): String {
        val id = UUID.randomUUID().toString()
        val r = TransferRecord(id, Direction.OUT, name, size, mime, sha256, TransferState.WAITING, ref = sourceRef, createdAt = clock())
        records[id] = r
        persist()
        if (wire != null) sendOffer(r) else notifyChange()
        return id
    }

    fun pause(id: String) {
        val r = records[id] ?: return
        if (r.state.isFinal || r.state == TransferState.PAUSED) return
        wire?.json(Types.FILE_PAUSE, FilePause(UUID.fromString(id)))
        if (r.direction == Direction.IN) ins[id]?.let { syncIn(id, it) }
        update(r.copy(state = TransferState.PAUSED, pausedBy = PausedBy.LOCAL))
        persist()
    }

    fun resume(id: String) {
        val r = records[id] ?: return
        if (r.state != TransferState.PAUSED) return
        val w = update(r.copy(state = TransferState.WAITING, pausedBy = null))
        persist()
        if (wire == null) return
        if (r.direction == Direction.OUT) sendOffer(w) // receiver replies accept(offset)
        else ins[id]?.let { acceptAt(w, it) } ?: wire?.json(Types.FILE_RESUME, FileResume(UUID.fromString(id)))
    }

    fun cancel(id: String) {
        val r = records[id] ?: return
        if (r.state.isFinal) return
        wire?.json(Types.FILE_CANCEL, FileCancel(UUID.fromString(id), "user_cancelled"))
        finishLocal(r, TransferState.CANCELLED, null)
    }

    /** Retry a failed outgoing transfer (the receiver decides where to continue). */
    fun retry(id: String) {
        val r = records[id] ?: return
        if (r.direction != Direction.OUT || r.state != TransferState.FAILED) return
        outs.remove(id)
        val w = update(r.copy(state = TransferState.WAITING, error = null, finishedAt = null))
        persist()
        if (wire != null) sendOffer(w)
    }

    fun clearHistory() {
        records.values.filter { it.state.isFinal }.map { it.id }.forEach { records.remove(it) }
        persist(); notifyChange()
    }

    // ---------------------------------------------------------------- inbound

    fun onMessage(env: Envelope) {
        try {
            when (env.type) {
                Types.FILE_OFFER.type -> onOffer(env.decode(Types.FILE_OFFER))
                Types.FILE_ACCEPT.type -> onAccept(env.decode(Types.FILE_ACCEPT))
                Types.FILE_REJECT.type -> env.decode(Types.FILE_REJECT).let { m ->
                    records[m.transferId.toString()]?.let { finishLocal(it, TransferState.FAILED, m.reason.wire) }
                }
                Types.FILE_ACK.type -> onAck(env.decode(Types.FILE_ACK))
                Types.FILE_NACK.type -> onNack(env.decode(Types.FILE_NACK))
                Types.FILE_PAUSE.type -> onRemotePause(env.decode(Types.FILE_PAUSE).transferId.toString())
                Types.FILE_RESUME.type -> onRemoteResume(env.decode(Types.FILE_RESUME).transferId.toString())
                Types.FILE_CANCEL.type -> env.decode(Types.FILE_CANCEL).let { m ->
                    records[m.transferId.toString()]?.let { if (!it.state.isFinal) finishLocal(it, TransferState.CANCELLED, "cancelled_by_peer") }
                }
                Types.FILE_DONE.type -> onDone(env.decode(Types.FILE_DONE).transferId.toString())
                Types.FILE_RESULT.type -> onResult(env.decode(Types.FILE_RESULT))
            }
        } catch (_: ProtocolException) {
            // Malformed file message: ignore (the session layer already validated the envelope).
        }
    }

    fun onBinary(bytes: ByteArray) {
        val frame = try {
            ChunkFrame.decode(bytes)
        } catch (e: ChunkFrame.DecodeError.CrcMismatch) {
            ins[e.transferId.toString()]?.let { nack(e.transferId.toString(), it) }
            return
        } catch (_: ChunkFrame.DecodeError) {
            return
        }
        val id = frame.transferId.toString()
        val r = records[id] ?: return
        val st = ins[id] ?: return
        if (r.state != TransferState.ACTIVE) return
        when {
            frame.offset == st.expected -> {
                val sink = st.sink ?: return
                if (st.expected + frame.payload.size > r.size) { nack(id, st); return }
                try {
                    sink.write(frame.offset, frame.payload)
                } catch (_: Exception) {
                    failAndTell(r, "io_error"); return // disk full / storage removed: stop cleanly
                }
                st.hasher?.update(frame.payload)
                st.expected += frame.payload.size
                if (st.expected - st.synced >= syncEveryBytes) syncIn(id, st)
                wire?.json(Types.FILE_ACK, FileAck(frame.transferId, st.expected))
                progress(records[id]!!, st.expected) // re-read: syncIn may have advanced the durable offset
            }
            frame.offset < st.expected -> Unit // duplicate after a rewind: already have it
            else -> nack(id, st) // gap: something was lost
        }
    }

    /** Call about once a second: stall detection, retries with backoff, verify timeouts. */
    fun tick() {
        val now = clock()
        for (r in records.values.toList()) {
            if (r.direction != Direction.OUT) continue
            val o = outs[r.id] ?: continue
            when (r.state) {
                TransferState.ACTIVE -> {
                    if (o.retryAt > 0) {
                        if (now >= o.retryAt) { o.retryAt = 0; o.next = r.offset; o.lastProgress = now; pump(r.id) }
                    } else if (now - o.lastProgress > stallMs) {
                        o.retries++
                        if (o.retries > maxRetries) {
                            finishLocal(r, TransferState.FAILED, "transfer_failed")
                        } else {
                            o.retryAt = now + (1000L shl (o.retries - 1)) // 1, 2, 4, 8, 16 s
                        }
                    }
                }
                TransferState.VERIFYING -> if (now - o.doneSentAt > verifyTimeoutMs) {
                    o.doneSentAt = now
                    wire?.json(Types.FILE_DONE, FileDone(UUID.fromString(r.id))) // idempotent
                }
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- sender side

    private fun sendOffer(r: TransferRecord) {
        val w = wire ?: return
        w.json(Types.FILE_OFFER, FileOffer(UUID.fromString(r.id), r.name, r.size, r.mime, r.sha256, chunkSize, null))
        if (r.state != TransferState.WAITING) update(r.copy(state = TransferState.WAITING))
    }

    private fun onAccept(m: FileAccept) {
        val id = m.transferId.toString()
        log("accept ${id.take(8)} offset=${m.offset} localState=${records[id]?.state}")
        val r = records[id] ?: return
        if (r.direction != Direction.OUT || r.state.isFinal) return
        if (r.state == TransferState.PAUSED && r.pausedBy == PausedBy.LOCAL) return
        val o = outs.getOrPut(id) { Out() }
        if (o.source == null) {
            o.source = r.ref?.let { runCatching { io.openSource(it) }.getOrNull() }
            if (o.source == null) { failAndTell(r, "source_unavailable"); return }
        }
        val start = m.offset.coerceIn(0, r.size)
        o.next = start; o.retries = 0; o.retryAt = 0; o.lastProgress = clock()
        update(r.copy(state = TransferState.ACTIVE, pausedBy = null, offset = start, transferred = start))
        persist()
        pump(id)
    }

    private fun onAck(m: FileAck) {
        val id = m.transferId.toString()
        val r = records[id] ?: return
        val o = outs[id] ?: return
        if (r.state != TransferState.ACTIVE) return
        if (m.offset > r.offset && m.offset <= r.size) {
            o.lastProgress = clock(); o.retries = 0; o.retryAt = 0
            if (o.next < m.offset) o.next = m.offset
            progress(r, m.offset, ackOffset = m.offset)
            pump(id)
        }
    }

    private fun onNack(m: FileNack) {
        val id = m.transferId.toString()
        val r = records[id] ?: return
        val o = outs[id] ?: return
        if (r.state != TransferState.ACTIVE) return
        // Go-back-N: resend from what the receiver says it expects.
        val at = m.offset.coerceIn(r.offset, r.size)
        o.next = at
        o.lastProgress = clock()
        pump(id)
    }

    private fun pump(id: String) {
        val r = records[id] ?: return
        val o = outs[id] ?: return
        val src = o.source ?: return
        val w = wire ?: return
        if (r.state != TransferState.ACTIVE || o.retryAt > 0) return
        val limit = window.toLong() * chunkSize
        while (o.next < r.size && o.next - records[id]!!.offset < limit) {
            val data = try {
                src.read(o.next, minOf(chunkSize.toLong(), r.size - o.next).toInt())
            } catch (_: Exception) { ByteArray(0) }
            if (data.isEmpty()) { failAndTell(r, "source_read_failed"); return }
            w.binary(ChunkFrame(UUID.fromString(id), o.next, data).encode())
            o.next += data.size
        }
        val cur = records[id]!!
        if (cur.offset >= cur.size) {
            o.doneSentAt = clock()
            w.json(Types.FILE_DONE, FileDone(UUID.fromString(id)))
            update(cur.copy(state = TransferState.VERIFYING))
        }
    }

    private fun onResult(m: FileResult) {
        val r = records[m.transferId.toString()] ?: return
        if (r.direction != Direction.OUT || r.state.isFinal) return
        if (m.ok) finishLocal(r.copy(savedName = m.savedName, offset = r.size, transferred = r.size), TransferState.DONE, null)
        else finishLocal(r.copy(offset = 0, transferred = 0), TransferState.FAILED, m.error?.wire ?: "verify_failed")
    }

    private fun onRemotePause(id: String) {
        val r = records[id] ?: return
        if (r.state.isFinal) return
        if (r.direction == Direction.IN) ins[id]?.let { syncIn(id, it) }
        update(r.copy(state = TransferState.PAUSED, pausedBy = r.pausedBy ?: PausedBy.REMOTE))
        persist()
    }

    private fun onRemoteResume(id: String) {
        val r = records[id] ?: return
        if (r.state != TransferState.PAUSED || r.pausedBy == PausedBy.LOCAL) return
        if (r.direction == Direction.IN) {
            update(r.copy(state = TransferState.WAITING, pausedBy = null))
            ins[id]?.let { acceptAt(records[id]!!, it) }
        } else {
            sendOffer(update(r.copy(state = TransferState.WAITING, pausedBy = null)))
        }
    }

    // ---------------------------------------------------------------- receiver side

    private fun onOffer(offer: FileOffer) {
        val id = offer.transferId.toString()
        log("offer ${id.take(8)} existing=${records[id]?.state} expected=${ins[id]?.expected}")
        val w = wire ?: return
        val existing = records[id]
        if (existing != null) {
            when {
                existing.state == TransferState.DONE ->
                    w.json(Types.FILE_RESULT, FileResult(offer.transferId, true, existing.savedName))
                existing.state == TransferState.CANCELLED ->
                    w.json(Types.FILE_CANCEL, FileCancel(offer.transferId, "cancelled"))
                existing.state == TransferState.PAUSED && existing.pausedBy == PausedBy.LOCAL ->
                    w.json(Types.FILE_PAUSE, FilePause(offer.transferId))
                else -> {
                    // Resume (reconnect / restart / retry after failure).
                    val st = ins[id] ?: In().also { ins[id] = it }
                    if (st.sink == null || existing.state == TransferState.FAILED) {
                        val from = if (existing.state == TransferState.FAILED) 0 else existing.offset
                        st.sink?.close()
                        st.sink = existing.ref?.let { io.reopenSink(it, existing.name, from) }
                            ?: runCatching { io.createSink(offer) }.getOrNull()
                        st.expected = if (existing.ref != null && st.sink?.ref == existing.ref) from else 0
                        st.synced = st.expected
                        st.hasher = if (st.expected == 0L) MessageDigest.getInstance("SHA-256") else null
                    }
                    if (st.sink == null) { w.json(Types.FILE_REJECT, FileReject(offer.transferId, FileRejectReason.BUSY)); return }
                    val r = update(existing.copy(state = TransferState.WAITING, pausedBy = null, error = null, finishedAt = null,
                        ref = st.sink!!.ref, offset = st.synced))
                    acceptAt(r, st)
                }
            }
            return
        }
        val sink = try {
            io.createSink(offer)
        } catch (_: FileIO.InsufficientSpace) {
            w.json(Types.FILE_REJECT, FileReject(offer.transferId, FileRejectReason.INSUFFICIENT_SPACE)); return
        } catch (_: Exception) {
            w.json(Types.FILE_REJECT, FileReject(offer.transferId, FileRejectReason.BUSY)); return
        }
        ins[id] = In(sink = sink, hasher = MessageDigest.getInstance("SHA-256"))
        val r = TransferRecord(id, Direction.IN, offer.name, offer.size, offer.mime, offer.sha256, TransferState.WAITING,
            ref = sink.ref, createdAt = clock())
        records[id] = r
        acceptAt(r, ins[id]!!)
    }

    private fun acceptAt(r: TransferRecord, st: In) {
        log("acceptAt ${r.id.take(8)} expected=${st.expected} synced=${st.synced}")
        // Continue from what is durably on disk; anything after it will be re-sent.
        syncIn(r.id, st)
        update(r.copy(state = TransferState.ACTIVE, offset = st.synced, transferred = st.expected))
        persist()
        wire?.json(Types.FILE_ACCEPT, FileAccept(UUID.fromString(r.id), st.expected))
        if (r.size == 0L) Unit // sender will send done right away
    }

    private fun onDone(id: String) {
        val r = records[id] ?: return
        val w = wire ?: return
        if (r.direction != Direction.IN) return
        if (r.state == TransferState.DONE) { w.json(Types.FILE_RESULT, FileResult(UUID.fromString(id), true, r.savedName)); return }
        if (r.state != TransferState.ACTIVE) return
        val st = ins[id] ?: return
        val sink = st.sink ?: return
        update(r.copy(state = TransferState.VERIFYING))
        sink.sync()
        val sizeOk = st.expected == r.size
        val hash = if (sizeOk) (st.hasher?.digest()?.let(::hex) ?: sink.sha256()) else null
        st.hasher = null
        if (sizeOk && hash == r.sha256) {
            val saved = runCatching { sink.commit() }.getOrNull()
            if (saved == null) {
                w.json(Types.FILE_RESULT, FileResult(UUID.fromString(id), false, error = FileResultError.IO_ERROR))
                finishLocal(r, TransferState.FAILED, "io_error")
                return
            }
            ins.remove(id)
            w.json(Types.FILE_RESULT, FileResult(UUID.fromString(id), true, saved))
            finish(r.copy(savedName = saved, offset = r.size, transferred = r.size), TransferState.DONE, null)
        } else {
            val err = if (sizeOk) FileResultError.HASH_MISMATCH else FileResultError.SIZE_MISMATCH
            sink.discard()
            ins.remove(id)
            w.json(Types.FILE_RESULT, FileResult(UUID.fromString(id), false, error = err))
            finish(r.copy(offset = 0, transferred = 0, ref = null), TransferState.FAILED, err.wire)
        }
    }

    private fun nack(id: String, st: In) {
        val now = clock()
        if (now - st.lastNack < 1000) return // at most once per second per transfer
        st.lastNack = now
        wire?.json(Types.FILE_NACK, FileNack(UUID.fromString(id), st.expected))
    }

    private fun syncIn(id: String, st: In) {
        val sink = st.sink ?: return
        if (st.expected == st.synced) return
        try { sink.sync() } catch (_: Exception) { return } // keep the old durable offset
        st.synced = st.expected
        records[id]?.let { records[id] = it.copy(offset = st.synced) }
        persist()
    }

    // ---------------------------------------------------------------- helpers

    private fun failAndTell(r: TransferRecord, reason: String) {
        wire?.json(Types.FILE_CANCEL, FileCancel(UUID.fromString(r.id), reason))
        finishLocal(r, TransferState.FAILED, reason)
    }

    private fun finishLocal(r: TransferRecord, state: TransferState, error: String?) {
        if (r.direction == Direction.IN) {
            ins.remove(r.id)?.sink?.let { s -> if (state == TransferState.CANCELLED) s.discard() else { s.sync(); s.close() } }
        } else {
            outs.remove(r.id)?.source?.close()
        }
        finish(r, state, error)
    }

    private fun finish(r: TransferRecord, state: TransferState, error: String?) {
        records[r.id] = r.copy(state = state, error = error, finishedAt = clock(), pausedBy = null, bytesPerSec = 0)
        rate.remove(r.id)
        trimHistory()
        persist()
        notifyChange()
    }

    private fun progress(r: TransferRecord, transferred: Long, ackOffset: Long? = null) {
        val now = clock()
        val (t0, b0) = rate[r.id] ?: (now to transferred)
        var bps = r.bytesPerSec
        if (now - t0 >= 1000) {
            bps = (transferred - b0) * 1000 / (now - t0)
            rate[r.id] = now to transferred
        } else if (r.id !in rate) rate[r.id] = now to transferred
        records[r.id] = r.copy(transferred = transferred, offset = ackOffset ?: r.offset, bytesPerSec = bps)
        if (now - lastNotify >= 250) notifyChange()
    }

    private var lastNotify = 0L

    private fun update(r: TransferRecord): TransferRecord {
        records[r.id] = r
        notifyChange()
        return r
    }

    private fun trimHistory() {
        val finished = records.values.filter { it.state.isFinal }.sortedBy { it.finishedAt ?: it.createdAt }
        val extra = finished.size - historyLimit
        if (extra > 0) finished.take(extra).forEach { records.remove(it.id) }
    }

    private fun persist() = store.save(records.values.toList())

    private fun notifyChange() {
        lastNotify = clock()
        onChange(records.values.toList())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
