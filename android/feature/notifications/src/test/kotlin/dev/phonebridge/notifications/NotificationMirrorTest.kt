package dev.phonebridge.notifications

import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.protocol.NotifIcon
import dev.phonebridge.protocol.NotifRemoved
import dev.phonebridge.protocol.NotifSync
import dev.phonebridge.protocol.NotificationItem
import dev.phonebridge.protocol.Types
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationMirrorTest {
    private val own = "dev.phonebridge"

    private fun c(
        key: String = "k1", pkg: String = "com.whatsapp", title: String? = "Ravi", text: String? = "Lunch at 1?",
        at: Long = 1000, clearable: Boolean = true, summary: Boolean = false, media: Boolean = false,
        importance: Int = 3, sensitive: Boolean = false,
    ) = Candidate(key, pkg, "WhatsApp", title, text, null, at, clearable, summary, media, importance, sensitive)

    /** Records what would go to the Mac. */
    private class Wire(var up: Boolean = true) : NotificationMirror.Out {
        val sent = mutableListOf<Pair<String, MessagePayload>>()
        override fun <P : MessagePayload> send(mt: MessageType<P>, payload: P): Boolean {
            if (up) sent += mt.type to payload
            return up
        }
        val types get() = sent.map { it.first }
        inline fun <reified T> all() = sent.map { it.second }.filterIsInstance<T>()
    }

    private val iconCalls = mutableListOf<String>()
    private fun mirror(icon: (String) -> ByteArray? = { iconCalls += it; byteArrayOf(1, 2, 3) }) = NotificationMirror(own, icon)

    // ---------------------------------------------------------------- filter

    @Test
    fun `filter skips what isn't news`() {
        val f = NotificationFilter
        assertNull(f.skipReason(c(), own, emptySet()))
        assertEquals(NotificationFilter.Skip.OWN_APP, f.skipReason(c(pkg = own), own, emptySet()))
        assertEquals(NotificationFilter.Skip.NOT_CLEARABLE, f.skipReason(c(clearable = false), own, emptySet()))
        assertEquals(NotificationFilter.Skip.GROUP_SUMMARY, f.skipReason(c(summary = true), own, emptySet()))
        assertEquals(NotificationFilter.Skip.MEDIA, f.skipReason(c(media = true), own, emptySet()))
        assertEquals(NotificationFilter.Skip.LOW_IMPORTANCE, f.skipReason(c(importance = 1), own, emptySet()))
        assertNull(f.skipReason(c(importance = Candidate.IMPORTANCE_UNKNOWN), own, emptySet()))
        assertEquals(NotificationFilter.Skip.APP_TURNED_OFF, f.skipReason(c(), own, setOf("com.whatsapp")))
        assertEquals(NotificationFilter.Skip.EMPTY, f.skipReason(c(title = " ", text = null), own, emptySet()))
    }

    @Test
    fun `items are trimmed for the wire and carry the redaction flag`() {
        val item = NotificationFilter.toItem(c(title = "  T  ", text = "x".repeat(5000), sensitive = true))
        assertEquals("T", item.title)
        assertEquals(NotificationFilter.MAX_TEXT, item.text!!.length)
        assertTrue(item.text!!.endsWith("…"))
        assertTrue(item.redacted)
        assertNull(NotificationFilter.toItem(c(text = "   ")).text)
    }

    // ---------------------------------------------------------------- session

    @Test
    fun `session start sends icons then a filtered, newest-first sync`() {
        val m = mirror()
        val w = Wire()
        m.start(w, listOf(c("a", at = 1), c("b", pkg = "com.gmail", at = 3), c("ongoing", clearable = false), c("c", at = 2)), emptySet())
        assertEquals(listOf("notif.icon", "notif.icon", "notif.sync"), w.types)
        assertEquals(listOf("com.gmail", "com.whatsapp").sorted(), w.all<NotifIcon>().map { it.pkg }.sorted())
        assertEquals(listOf("b", "c", "a"), w.all<NotifSync>().single().items.map { it.key })
    }

    @Test
    fun `listener not bound yet - sync arrives when it binds`() {
        val m = mirror()
        val w = Wire()
        m.start(w, null, emptySet())
        assertTrue(w.sent.isEmpty())
        m.sync(listOf(c()), emptySet())
        assertEquals(listOf("notif.icon", "notif.sync"), w.types)
    }

    @Test
    fun `icon goes once per package per session, before the first item`() {
        val m = mirror()
        val w = Wire()
        m.start(w, emptyList(), emptySet())
        m.posted(c("1"), emptySet())
        m.posted(c("2", text = "again"), emptySet())
        assertEquals(listOf("notif.sync", "notif.icon", "notif.posted", "notif.posted"), w.types)

        // New session: the Mac may have restarted, so icons are sent again.
        m.stop()
        val w2 = Wire()
        m.start(w2, emptyList(), emptySet())
        m.posted(c("3"), emptySet())
        assertEquals(listOf("notif.sync", "notif.icon", "notif.posted"), w2.types)
    }

    @Test
    fun `identical re-posts don't re-alert the Mac, changed content does`() {
        val m = mirror()
        val w = Wire()
        m.start(w, emptyList(), emptySet())
        m.posted(c(at = 1), emptySet())
        m.posted(c(at = 2), emptySet()) // same content, new timestamp (silent refresh)
        m.posted(c(at = 3, text = "Lunch at 2?"), emptySet())
        assertEquals(2, w.all<NotificationItem>().size)
        assertEquals("Lunch at 2?", w.all<NotificationItem>().last().text)
    }

    @Test
    fun `removal is only sent for notifications the Mac has`() {
        val m = mirror()
        val w = Wire()
        m.start(w, emptyList(), emptySet())
        m.removed("never-sent")
        m.posted(c("k"), emptySet())
        m.posted(c("ongoing", clearable = false), emptySet())
        m.removed("ongoing")
        m.removed("k")
        m.removed("k") // twice: only one removal
        assertEquals(listOf("k"), w.all<NotifRemoved>().map { it.key })
    }

    @Test
    fun `a mirrored notification that turns ongoing is taken off the Mac`() {
        val m = mirror()
        val w = Wire()
        m.start(w, emptyList(), emptySet())
        m.posted(c("k"), emptySet())
        m.posted(c("k", clearable = false), emptySet())
        assertEquals(listOf("k"), w.all<NotifRemoved>().map { it.key })
    }

    @Test
    fun `turning an app off and resyncing removes it from the Mac`() {
        val m = mirror()
        val w = Wire()
        val shade = listOf(c("w"), c("g", pkg = "com.gmail"))
        m.start(w, shade, emptySet())
        m.sync(shade, setOf("com.whatsapp"))
        assertEquals(listOf("g"), w.all<NotifSync>().last().items.map { it.key })
        m.posted(c("w2"), setOf("com.whatsapp"))
        assertTrue(w.all<NotificationItem>().isEmpty())
    }

    @Test
    fun `nothing is sent or queued without a session`() {
        val m = mirror()
        m.posted(c(), emptySet())
        m.removed("k1")
        m.sync(listOf(c()), emptySet())
        val w = Wire()
        m.start(w, emptyList(), emptySet())
        assertEquals(listOf("notif.sync"), w.types) // the earlier post wasn't queued
        assertTrue(w.all<NotifSync>().single().items.isEmpty())
    }

    @Test
    fun `missing or oversized icons are skipped, items still go`() {
        val big = mirror(icon = { ByteArray(NotifIcon.MAX_BYTES + 1) })
        val w = Wire()
        big.start(w, emptyList(), emptySet())
        big.posted(c(), emptySet())
        assertEquals(listOf("notif.sync", "notif.posted"), w.types)

        val none = mirror(icon = { error("no icon") })
        val w2 = Wire()
        none.start(w2, emptyList(), emptySet())
        none.posted(c(), emptySet())
        assertEquals(listOf("notif.sync", "notif.posted"), w2.types)
    }

    @Test
    fun `a send that fails (session ending) is retried on the next post`() {
        val m = mirror()
        val w = Wire()
        m.start(w, emptyList(), emptySet())
        w.up = false
        m.posted(c(), emptySet())
        w.up = true
        m.posted(c(), emptySet())
        assertEquals(1, w.all<NotificationItem>().size)
    }
}
