package dev.phonebridge.notifications

import dev.phonebridge.protocol.B64
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.protocol.NotifIcon
import dev.phonebridge.protocol.NotifRemoved
import dev.phonebridge.protocol.NotifSync
import dev.phonebridge.protocol.NotificationItem
import dev.phonebridge.protocol.Types

/**
 * A notification as read from the system, reduced to plain data so the rules below are unit-testable
 * without Android. Built by [SbnReader] from a StatusBarNotification + its ranking.
 */
data class Candidate(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val postedAt: Long,
    val clearable: Boolean,
    val groupSummary: Boolean,
    val media: Boolean,
    /** `NotificationManager.IMPORTANCE_*` of its channel; [IMPORTANCE_UNKNOWN] if not known. */
    val importance: Int = IMPORTANCE_UNKNOWN,
    /** Android 15+ hid the content (OTP / sensitive) from listeners like us. */
    val sensitive: Boolean = false,
) {
    companion object {
        const val IMPORTANCE_UNKNOWN = -1000
        const val IMPORTANCE_MIN = 1 // NotificationManager.IMPORTANCE_MIN
    }
}

/** Which notifications are mirrored (PROTOCOL.md §10) and how they're trimmed for the wire. */
object NotificationFilter {
    enum class Skip { OWN_APP, NOT_CLEARABLE, GROUP_SUMMARY, MEDIA, LOW_IMPORTANCE, APP_TURNED_OFF, EMPTY }

    const val MAX_TITLE = 256
    const val MAX_TEXT = 2000
    const val MAX_SUBTEXT = 128
    const val MAX_SYNC_ITEMS = 100

    fun skipReason(c: Candidate, ownPkg: String, disabledApps: Set<String>): Skip? = when {
        c.pkg == ownPkg -> Skip.OWN_APP
        // Ongoing / foreground-service / "can't swipe away" notifications are status, not news.
        !c.clearable -> Skip.NOT_CLEARABLE
        c.groupSummary -> Skip.GROUP_SUMMARY
        c.media -> Skip.MEDIA
        c.importance != Candidate.IMPORTANCE_UNKNOWN && c.importance <= Candidate.IMPORTANCE_MIN -> Skip.LOW_IMPORTANCE
        c.pkg in disabledApps -> Skip.APP_TURNED_OFF
        c.title.isNullOrBlank() && c.text.isNullOrBlank() -> Skip.EMPTY
        else -> null
    }

    fun toItem(c: Candidate) = NotificationItem(
        key = c.key,
        pkg = c.pkg,
        app = c.app.take(MAX_TITLE),
        title = c.title?.trim()?.take(MAX_TITLE)?.ifBlank { null },
        text = c.text?.trim()?.let { if (it.length > MAX_TEXT) it.take(MAX_TEXT - 1) + "…" else it }?.ifBlank { null },
        subText = c.subText?.trim()?.take(MAX_SUBTEXT)?.ifBlank { null },
        postedAt = c.postedAt,
        redacted = c.sensitive,
    )
}

/**
 * The phone's view of what the Mac currently shows, for one session. Not queued while offline: the
 * full state is re-sent with `notif.sync` on every session start (PROTOCOL.md §10, §12).
 * Thread-safe: listener callbacks (main thread) and session callbacks may interleave.
 */
class NotificationMirror(
    private val ownPkg: String,
    /** PNG ≤ 64×64, ≤ 16 KiB, or null if the app icon can't be rendered. */
    private val icon: (pkg: String) -> ByteArray?,
) {
    interface Out {
        fun <P : MessagePayload> send(mt: MessageType<P>, payload: P): Boolean
    }

    private var out: Out? = null
    private val iconsSent = HashSet<String>()
    /** What the Mac shows right now, by key. */
    private val onMac = LinkedHashMap<String, NotificationItem>()

    val isLive: Boolean @Synchronized get() = out != null

    /**
     * New session. [active] is the current notification shade, or null if the listener isn't bound yet
     * (then [sync] is called once it is).
     */
    @Synchronized
    fun start(out: Out, active: List<Candidate>?, disabledApps: Set<String>) {
        this.out = out
        iconsSent.clear(); onMac.clear()
        if (active != null) syncLocked(active, disabledApps)
    }

    @Synchronized
    fun stop() { out = null; iconsSent.clear(); onMac.clear() }

    /** Replace the Mac's state with the current shade (session start, listener bound, filter changed). */
    @Synchronized
    fun sync(active: List<Candidate>, disabledApps: Set<String>) { if (out != null) syncLocked(active, disabledApps) }

    private fun syncLocked(active: List<Candidate>, disabledApps: Set<String>) {
        val o = out ?: return
        val items = active.asSequence()
            .filter { NotificationFilter.skipReason(it, ownPkg, disabledApps) == null }
            .sortedByDescending { it.postedAt }
            .take(NotificationFilter.MAX_SYNC_ITEMS)
            .map(NotificationFilter::toItem)
            .toList()
        items.map { it.pkg }.distinct().forEach { ensureIcon(o, it) }
        if (o.send(Types.NOTIF_SYNC, NotifSync(items))) {
            onMac.clear()
            items.forEach { onMac[it.key] = it }
        }
    }

    @Synchronized
    fun posted(c: Candidate, disabledApps: Set<String>) {
        val o = out ?: return
        if (NotificationFilter.skipReason(c, ownPkg, disabledApps) != null) {
            // e.g. it turned into an ongoing notification: it shouldn't stay on the Mac either.
            if (onMac.remove(c.key) != null) o.send(Types.NOTIF_REMOVED, NotifRemoved(c.key))
            return
        }
        val item = NotificationFilter.toItem(c)
        // Apps often re-post identical content (progress ticks, "silent" refreshes): don't re-alert the Mac.
        if (onMac[c.key]?.copy(postedAt = 0) == item.copy(postedAt = 0)) return
        ensureIcon(o, c.pkg)
        if (o.send(Types.NOTIF_POSTED, item)) onMac[c.key] = item
    }

    @Synchronized
    fun removed(key: String) {
        val o = out ?: return
        if (onMac.remove(key) != null) o.send(Types.NOTIF_REMOVED, NotifRemoved(key))
    }

    /** `notif.icon` goes once per package per session, before that package's first item (§10). */
    private fun ensureIcon(o: Out, pkg: String) {
        if (pkg in iconsSent) return
        iconsSent += pkg
        val png = runCatching { icon(pkg) }.getOrNull() ?: return
        if (png.size <= NotifIcon.MAX_BYTES) o.send(Types.NOTIF_ICON, NotifIcon(pkg, B64(png)))
    }
}
