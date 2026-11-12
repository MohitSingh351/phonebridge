package dev.phonebridge.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.phonebridge.protocol.ClipboardSet
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.Features
import dev.phonebridge.protocol.PBJson
import dev.phonebridge.protocol.ProtocolException
import dev.phonebridge.protocol.Types
import dev.phonebridge.transport.Feature
import dev.phonebridge.transport.FeatureLink
import java.util.UUID

/** Implemented by the Application so the tile/activity can reach the feature without DI. */
interface ClipboardHost {
    val clipboard: ClipboardFeature
}

/**
 * Clipboard sync, phone side.
 *  - Mac → phone: write to the clipboard (allowed in the background) and show a small, silent,
 *    self-dismissing notification.
 *  - Phone → Mac: [sendFromUser], called by the invisible activity (tile, share sheet, notification).
 *  - Offline: the latest phone item waits up to 5 minutes and is sent on reconnect (PROTOCOL.md §12).
 */
class ClipboardFeature(
    private val context: Context,
    myDeviceId: UUID,
    private val showPreview: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : Feature {
    override val id = Features.CLIPBOARD

    private val core = ClipboardSyncCore(myDeviceId)
    private val prefs = context.getSharedPreferences("pb.clipboard", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var link: FeatureLink? = null

    enum class SendResult { SENT, QUEUED, ALREADY_ON_MAC, EMPTY, TOO_LARGE }

    // ---------------------------------------------------------------- session

    override suspend fun onSessionStarted(link: FeatureLink) {
        this.link = link
        flushPending()
    }

    override fun onSessionEnded() { link = null }

    override suspend fun onMessage(envelope: Envelope) {
        if (envelope.type != Types.CLIPBOARD_SET.type) return
        val msg = try { envelope.decode(Types.CLIPBOARD_SET) } catch (_: ProtocolException) { return } // hash verified
        val text = core.onRemote(msg) ?: return
        main.post { applyToClipboard(text) }
    }

    // ---------------------------------------------------------------- phone → Mac

    fun sendFromUser(text: String?): SendResult {
        return when (val out = core.onLocalCopy(text.orEmpty())) {
            ClipboardSyncCore.Outgoing.Empty -> SendResult.EMPTY
            ClipboardSyncCore.Outgoing.TooLarge -> SendResult.TOO_LARGE
            ClipboardSyncCore.Outgoing.CameFromOtherDevice, ClipboardSyncCore.Outgoing.AlreadySent -> SendResult.ALREADY_ON_MAC
            is ClipboardSyncCore.Outgoing.Send -> {
                if (link?.send(Types.CLIPBOARD_SET, out.message) == true) {
                    clearPending(); SendResult.SENT
                } else {
                    savePending(out.message); SendResult.QUEUED
                }
            }
        }
    }

    private fun flushPending() {
        val raw = prefs.getString(KEY_PENDING, null) ?: return
        val at = prefs.getLong(KEY_PENDING_AT, 0)
        if (now() - at > PENDING_TTL_MS) { clearPending(); return } // too old to be useful
        val msg = runCatching { PBJson.decodeFromString(ClipboardSet.serializer(), raw) }.getOrNull()
        if (msg != null && link?.send(Types.CLIPBOARD_SET, msg) == true) clearPending()
    }

    private fun savePending(msg: ClipboardSet) {
        // Only the newest item matters; a queue of stale clipboards would just overwrite each other on the Mac.
        prefs.edit().putString(KEY_PENDING, PBJson.encodeToString(ClipboardSet.serializer(), msg))
            .putLong(KEY_PENDING_AT, now()).apply()
    }

    private fun clearPending() { prefs.edit().remove(KEY_PENDING).remove(KEY_PENDING_AT).apply() }

    // ---------------------------------------------------------------- Mac → phone

    private fun applyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("From Mac", text)
        if (!showPreview()) {
            // Ask Android not to show the clipboard preview overlay either (Android 13+).
            clip.description.extras = PersistableBundle().apply {
                putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        notifyReceived(text)
    }

    private fun notifyReceived(text: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel()
        val body = if (showPreview()) "“${text.lineSequence().first().take(60)}${if (text.length > 60) "…" else ""}”" else "Ready to paste"
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Copied from Mac")
            .setContentText(body)
            .setSilent(true)
            .setTimeoutAfter(4_000)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Clipboard from Mac", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A brief note when something copied on your Mac is ready to paste."
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "clipboard"
        const val NOTIFICATION_ID = 2
        const val PENDING_TTL_MS = 5 * 60 * 1000L
        private const val KEY_PENDING = "pending"
        private const val KEY_PENDING_AT = "pendingAt"
    }
}
