package dev.phonebridge.clipboard

import dev.phonebridge.protocol.ClipboardSet
import java.util.UUID

/**
 * Loop-prevention rules for clipboard sync (PROTOCOL.md §8), as pure logic shared by every send path.
 * Mirrors mac `ClipboardSyncCore`.
 *
 * - An item received from the other device and applied locally must never be sent back.
 * - Copying the same text twice in a row sends it once.
 * - After a *different* item was copied, re-copying an older text sends it again (deliberate re-sync).
 */
class ClipboardSyncCore(private val myDeviceId: UUID) {
    /** Hash of the last item received from the other side and written to our clipboard. */
    private var lastAppliedHash: String? = null
    /** Hash of the last item we sent. */
    private var lastSentHash: String? = null

    sealed interface Outgoing {
        data class Send(val message: ClipboardSet) : Outgoing
        /** Same as what the other device just gave us — sending it back would loop. */
        data object CameFromOtherDevice : Outgoing
        data object AlreadySent : Outgoing
        data object Empty : Outgoing
        data object TooLarge : Outgoing
    }

    @Synchronized
    fun onLocalCopy(text: String): Outgoing {
        if (text.isEmpty()) return Outgoing.Empty
        if (text.toByteArray(Charsets.UTF_8).size > ClipboardSet.MAX_TEXT_BYTES) return Outgoing.TooLarge
        val hash = ClipboardSet.hashOf(text)
        if (hash == lastAppliedHash) return Outgoing.CameFromOtherDevice
        if (hash == lastSentHash) return Outgoing.AlreadySent
        lastAppliedHash = null // a different local item: re-copying the old remote text later is deliberate
        lastSentHash = hash
        return Outgoing.Send(ClipboardSet(originDeviceId = myDeviceId, hash = hash, text = text))
    }

    /** Returns the text to write to the local clipboard, or null to ignore (our own echo). */
    @Synchronized
    fun onRemote(message: ClipboardSet): String? {
        if (message.originDeviceId == myDeviceId) return null
        lastAppliedHash = message.hash
        lastSentHash = null
        return message.text
    }
}
