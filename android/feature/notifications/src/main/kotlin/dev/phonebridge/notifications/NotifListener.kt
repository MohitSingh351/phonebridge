package dev.phonebridge.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The system binds this once the user grants Notification access. It only forwards events to the feature
 * (which lives in the Application), so the feature works the same whether or not the listener is bound.
 */
class NotifListener : NotificationListenerService() {
    private val feature: NotificationsFeature? get() = (application as? NotificationsHost)?.notifications

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
        feature?.listenerConnected(this)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "listener disconnected")
        feature?.listenerDisconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        feature?.posted(this, sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        feature?.removed(sbn.key)
    }

    /** Current shade as candidates (empty if the system refuses, e.g. while unbinding). */
    internal fun snapshot(reader: SbnReader): List<Candidate> = runCatching {
        val ranking = currentRanking
        activeNotifications.orEmpty().map { reader.read(it, ranking) }
    }.onFailure { Log.w(TAG, "snapshot failed", it) }.getOrDefault(emptyList())

    internal fun dismiss(key: String) {
        runCatching { cancelNotification(key) }.onFailure { Log.w(TAG, "dismiss failed", it) }
    }

    companion object {
        const val TAG = "PhoneBridge.notif"

        fun component(context: Context) = ComponentName(context, NotifListener::class.java)

        /** Ask the system to bind us again (e.g. after the process was killed while access stayed granted). */
        fun rebind(context: Context) {
            runCatching { requestRebind(component(context)) }
        }
    }
}

/** Turns a StatusBarNotification into a [Candidate]; renders and caches app names and icons. */
internal class SbnReader(private val context: Context) {
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, ByteArray>()

    fun read(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?): Candidate {
        val n = sbn.notification
        val x = n.extras
        val ranking = rankingMap?.let { m ->
            NotificationListenerService.Ranking().takeIf { m.getRanking(sbn.key, it) }
        }
        val title = (x.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val stickyFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE
        return Candidate(
            key = sbn.key,
            pkg = sbn.packageName,
            app = label(sbn.packageName),
            title = title,
            text = text,
            subText = x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt = sbn.postTime,
            clearable = sbn.isClearable && (n.flags and stickyFlags) == 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            media = x.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT,
            importance = ranking?.importance ?: Candidate.IMPORTANCE_UNKNOWN,
            // Android 15+ already swaps sensitive content (one-time codes…) for its own placeholder text before
            // companion listeners see it, and exposes no public flag for it, so [Candidate.sensitive] stays false.
        )
    }

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        val pm = context.packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: pkg
    }

    /** App icon as a 64×64 PNG (PROTOCOL.md §10: ≤ 64×64, ≤ 16 KiB). */
    fun icon(pkg: String): ByteArray? = icons[pkg] ?: run {
        val drawable = try { context.packageManager.getApplicationIcon(pkg) } catch (_: PackageManager.NameNotFoundException) { return null }
        for (size in intArrayOf(64, 48, 32)) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bmp))
            val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bmp.recycle()
            if (png.size <= 16 * 1024) { icons[pkg] = png; return png }
        }
        null
    }
}
