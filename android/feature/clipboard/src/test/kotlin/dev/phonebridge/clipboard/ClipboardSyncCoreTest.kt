package dev.phonebridge.clipboard

import dev.phonebridge.protocol.ClipboardSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/** Clipboard loop tests (plan §7): two peers wired together in memory, exactly as on the wire. */
class ClipboardSyncCoreTest {
    private val phoneId = UUID.randomUUID()
    private val macId = UUID.randomUUID()

    /** A device: its sync core plus a fake system clipboard that reports every write as a local change. */
    private inner class Device(id: UUID) {
        val core = ClipboardSyncCore(id)
        var clipboard = ""
        lateinit var peer: Device
        var sent = 0

        /** The user (or the OS, after we write) changed the clipboard; the watcher reports it. */
        fun localChange(text: String) {
            clipboard = text
            val out = core.onLocalCopy(text)
            if (out is ClipboardSyncCore.Outgoing.Send) { sent++; peer.receive(out.message) }
        }

        fun receive(msg: ClipboardSet) {
            val text = core.onRemote(msg) ?: return
            localChange(text) // writing the clipboard fires the change watcher — the classic loop trigger
        }
    }

    private fun pair(): Pair<Device, Device> {
        val phone = Device(phoneId); val mac = Device(macId)
        phone.peer = mac; mac.peer = phone
        return phone to mac
    }

    @Test
    fun `1000 random copies each cross exactly once and never bounce`() {
        val (phone, mac) = pair()
        val r = Random(42)
        var expectedSends = 0
        var prevBy: Device? = null
        var prevText: String? = null
        repeat(1000) {
            val by = if (r.nextBoolean()) phone else mac
            // Small vocabulary so repeats, re-copies and ping-pong of the same text all happen.
            val text = "item-${r.nextInt(12)}"
            val other = if (by === phone) mac else phone
            // Expected: a send happens unless this device's clipboard already holds this exact text
            // (copying what's already there changes nothing / was just received or just sent).
            if (by.clipboard != text) expectedSends++
            by.localChange(text)
            assertEquals(by.clipboard, other.clipboard, "both clipboards must agree after every copy")
            prevBy = by; prevText = text
        }
        assertEquals(expectedSends, phone.sent + mac.sent, "each new item crosses exactly once")
        assertTrue(prevBy != null && prevText != null)
    }

    @Test
    fun `received item is not echoed back`() {
        val (phone, mac) = pair()
        mac.localChange("hello")
        assertEquals("hello", phone.clipboard)
        assertEquals(1, mac.sent)
        assertEquals(0, phone.sent, "phone must not send the Mac's own item back")
    }

    @Test
    fun `re-copying an older text after a different item syncs again`() {
        val (phone, mac) = pair()
        mac.localChange("A")         // phone gets A
        phone.localChange("B")       // Mac gets B
        phone.localChange("A")       // deliberate re-copy of A on the phone: must reach the Mac again
        assertEquals("A", mac.clipboard)
        assertEquals(2, phone.sent)
    }

    @Test
    fun `copying the same text twice sends once`() {
        val (phone, mac) = pair()
        phone.localChange("same")
        phone.localChange("same")
        assertEquals(1, phone.sent)
        assertEquals("same", mac.clipboard)
    }

    @Test
    fun `own echo by device id is ignored`() {
        val core = ClipboardSyncCore(phoneId)
        assertNull(core.onRemote(ClipboardSet.of(phoneId, "x")))
    }

    @Test
    fun `empty and oversized text are not sent`() {
        val core = ClipboardSyncCore(phoneId)
        assertEquals(ClipboardSyncCore.Outgoing.Empty, core.onLocalCopy(""))
        assertEquals(ClipboardSyncCore.Outgoing.TooLarge, core.onLocalCopy("x".repeat(ClipboardSet.MAX_TEXT_BYTES + 1)))
    }
}
