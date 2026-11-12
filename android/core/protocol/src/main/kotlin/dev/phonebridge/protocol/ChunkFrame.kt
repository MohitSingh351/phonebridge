package dev.phonebridge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Binary file-chunk frame (PROTOCOL.md §9.2): 36-byte big-endian header + payload. */
class ChunkFrame(val transferId: UUID, val offset: Long, val payload: ByteArray) {

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC0).put(MAGIC1).put(VERSION).put(KIND_FILE_CHUNK)
        buf.put(Uuids.toBytes(transferId))
        buf.putLong(offset)
        buf.putInt(payload.size)
        buf.putInt(crc32(payload).toInt())
        buf.put(payload)
        return buf.array()
    }

    override fun equals(other: Any?) = other is ChunkFrame && transferId == other.transferId &&
        offset == other.offset && payload.contentEquals(other.payload)
    override fun hashCode() = 31 * (31 * transferId.hashCode() + offset.hashCode()) + payload.contentHashCode()

    sealed class DecodeError(msg: String) : Exception(msg) {
        data object TooShort : DecodeError("frame too short") { private fun readResolve(): Any = TooShort }
        data object BadMagic : DecodeError("bad magic") { private fun readResolve(): Any = BadMagic }
        data class UnsupportedVersion(val version: Int) : DecodeError("unsupported frame version $version")
        data class UnknownKind(val kind: Int) : DecodeError("unknown frame kind $kind")
        data object LengthMismatch : DecodeError("length mismatch") { private fun readResolve(): Any = LengthMismatch }
        data object TooLarge : DecodeError("chunk too large") { private fun readResolve(): Any = TooLarge }
        /** CRC failed; header fields are reported so the receiver can send `file.nack`. */
        data class CrcMismatch(val transferId: UUID, val offset: Long) : DecodeError("crc mismatch at $offset")
    }

    companion object {
        const val HEADER_SIZE = 36
        private const val MAGIC0: Byte = 0x50 // 'P'
        private const val MAGIC1: Byte = 0x42 // 'B'
        const val VERSION: Byte = 1
        const val KIND_FILE_CHUNK: Byte = 1

        fun crc32(b: ByteArray): Long = CRC32().apply { update(b) }.value

        @Throws(DecodeError::class)
        fun decode(bytes: ByteArray): ChunkFrame {
            if (bytes.size < HEADER_SIZE) throw DecodeError.TooShort
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buf.get() != MAGIC0 || buf.get() != MAGIC1) throw DecodeError.BadMagic
            val version = buf.get()
            if (version != VERSION) throw DecodeError.UnsupportedVersion(version.toInt() and 0xFF)
            val kind = buf.get()
            if (kind != KIND_FILE_CHUNK) throw DecodeError.UnknownKind(kind.toInt() and 0xFF)
            val tid = ByteArray(16).also { buf.get(it) }.let(Uuids::fromBytes)
            val offset = buf.getLong()
            val length = buf.getInt().toLong() and 0xFFFFFFFFL
            val crc = buf.getInt().toLong() and 0xFFFFFFFFL
            if (length > PBConstants.MAX_CHUNK_PAYLOAD) throw DecodeError.TooLarge
            if (bytes.size.toLong() != HEADER_SIZE + length) throw DecodeError.LengthMismatch
            val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
            if (crc32(payload) != crc) throw DecodeError.CrcMismatch(tid, offset)
            return ChunkFrame(tid, offset, payload)
        }
    }
}
