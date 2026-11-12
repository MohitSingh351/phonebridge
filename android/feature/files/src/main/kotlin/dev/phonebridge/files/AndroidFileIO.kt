package dev.phonebridge.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.phonebridge.protocol.FileOffer
import dev.phonebridge.protocol.PBJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Android storage for transfers:
 *  - Sources are content URIs (share sheet / document picker), read positionally so resume can start anywhere.
 *  - Received files go to Download/PhoneBridge through MediaStore — no storage permission needed. The item
 *    is created "pending" (hidden from other apps) and written in place, then published on success.
 *    MediaStore resolves name clashes itself ("photo (1).jpg").
 */
class AndroidFileIO(private val context: Context) : FileIO {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Share-sheet URIs come with *temporary* access that Android revokes when the share activity closes.
     * We open them while access is valid and keep the descriptor for the whole transfer (an open fd stays
     * readable after the grant is revoked). Keyed by ref; released when the transfer finishes.
     */
    private val held = java.util.concurrent.ConcurrentHashMap<String, UriSource>()

    /**
     * Open now, while the share grant is valid. Call from the share activity before it finishes. Returns a ref
     * that is unique to this send (sending the same file twice must never share an open descriptor).
     */
    fun hold(uri: Uri): String? = runCatching {
        val ref = "$uri$HELD_MARK${java.util.UUID.randomUUID()}"
        held[ref] = UriSource(resolver, uri).also { it.held = true }
        ref
    }.getOrNull()

    fun release(ref: String) { held.remove(ref)?.reallyClose() }

    override fun openSource(ref: String): FileSource? =
        held[ref] ?: runCatching { UriSource(resolver, uriOf(ref)) }.getOrNull()

    private fun uriOf(ref: String): Uri = Uri.parse(ref.substringBefore(HELD_MARK))

    override fun createSink(offer: FileOffer): FileSink {
        if (freeBytes() < offer.size + RESERVE_BYTES) throw FileIO.InsufficientSpace()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, offer.name)
            put(MediaStore.MediaColumns.MIME_TYPE, offer.mime.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PhoneBridge")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore insert failed")
        return MediaStoreSink(resolver, uri, 0)
    }

    override fun reopenSink(ref: String, name: String, offset: Long): FileSink? =
        runCatching { MediaStoreSink(resolver, Uri.parse(ref), offset) }.getOrNull()

    private fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    /** Name, size and type of a content URI chosen by the user. */
    data class Meta(val name: String, val size: Long, val mime: String)

    fun describe(ref: String): Meta? = describe(uriOf(ref))

    fun describe(uri: Uri): Meta? {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        val safeName = (name ?: uri.lastPathSegment ?: "file").replace('/', '_').take(200).ifBlank { "file" }
        return Meta(safeName, size, resolver.getType(uri) ?: "application/octet-stream")
    }

    /** Streams the file once to compute its SHA-256 (done before offering, off the main thread). */
    fun sha256(ref: String): String {
        val src = openSource(ref) ?: error("cannot open $ref")
        val md = MessageDigest.getInstance("SHA-256")
        var off = 0L
        while (off < src.size) {
            val b = src.read(off, 1 shl 20)
            if (b.isEmpty()) break
            md.update(b); off += b.size
        }
        if (held[ref] !== src) src.close()
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val RESERVE_BYTES = 50L * 1024 * 1024 // keep the phone usable
        private const val HELD_MARK = "#pb-held="
    }
}

internal class UriSource(resolver: ContentResolver, uri: Uri) : FileSource {
    /** Held (share-sheet) sources ignore the engine's close; [AndroidFileIO.release] closes them. */
    var held = false
    private val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r") ?: error("cannot open $uri")
    private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel
    override val size: Long = pfd.statSize

    override fun read(offset: Long, max: Int): ByteArray {
        val buf = ByteBuffer.allocate(max)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() { if (!held) reallyClose() }
    fun reallyClose() { runCatching { channel.close() }; runCatching { pfd.close() } }
}

private class MediaStoreSink(private val resolver: ContentResolver, private val uri: Uri, offset: Long) : FileSink {
    override val ref: String = uri.toString()
    private val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "rw") ?: error("cannot open $uri")
    private val out = FileOutputStream(pfd.fileDescriptor)
    private val channel: FileChannel = out.channel

    init { channel.truncate(offset) } // drop anything after the last durable point

    override fun write(offset: Long, data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        var pos = offset
        while (buf.hasRemaining()) pos += channel.write(buf, pos)
    }

    override fun sync() { channel.force(false); runCatching { pfd.fileDescriptor.sync() } }

    override fun sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteBuffer.allocate(1 shl 20)
        var pos = 0L
        while (true) {
            buf.clear()
            val n = channel.read(buf, pos)
            if (n <= 0) break
            md.update(buf.array(), 0, n); pos += n
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    override fun commit(): String {
        sync(); close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "download"
    }

    override fun discard() { close(); runCatching { resolver.delete(uri, null, null) } }

    override fun close() { runCatching { channel.close() }; runCatching { pfd.close() } }
}

/** Transfer records in a small JSON file (≤ 200 entries; see the plan's decision log). */
class JsonTransferStore(context: Context) : TransferStore {
    private val file = File(context.filesDir, "transfers.json")
    private val serializer = ListSerializer(TransferRecord.serializer())

    override fun load(): List<TransferRecord> =
        runCatching { PBJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    override fun save(records: List<TransferRecord>) {
        val tmp = File(file.parentFile, "transfers.json.tmp")
        tmp.writeText(PBJson.encodeToString(serializer, records))
        tmp.renameTo(file) // atomic replace: never a half-written history
    }
}
