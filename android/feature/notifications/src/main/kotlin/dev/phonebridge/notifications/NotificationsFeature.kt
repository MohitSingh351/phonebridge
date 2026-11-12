package dev.phonebridge.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.phonebridge.protocol.CapabilityState
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.Features
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.protocol.ProtocolException
import dev.phonebridge.protocol.Types
import dev.phonebridge.transport.Feature
import dev.phonebridge.transport.FeatureLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Implemented by the Application so the listener service can reach the feature without DI. */
interface NotificationsHost {
    val notifications: NotificationsFeature
}

/**
 * Notification mirroring, phone side (PROTOCOL.md §10).
 *
 * Capability state follows the user's choices, so the Mac always knows why mirroring is off:
 *  - "Mirror notifications" switched off → `disabled`;
 *  - Notification access not granted (or revoked) → `permission_missing`;
 *  - otherwise `available`. Changes mid-session are reported via [onStateChanged] → `capabilities.changed`.
 */
class NotificationsFeature(
    private val context: Context,
    /** Called when [state] changes; the app forwards it to ConnectionManager.capabilitiesChanged(). */
    private val onStateChanged: () -> Unit,
) : Feature {
    override val id = Features.NOTIFICATIONS

    data class AppEntry(val pkg: String, val label: String, val mirrored: Boolean)
    data class Ui(val enabled: Boolean, val accessGranted: Boolean, val listening: Boolean, val apps: List<AppEntry>)

    private val prefs = context.getSharedPreferences("pb.notifications", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val reader = SbnReader(context)
    private val mirror = NotificationMirror(context.packageName, reader::icon)
    @Volatile private var listener: NotifListener? = null
    @Volatile private var lastState: CapabilityState? = null

    private val _ui = MutableStateFlow(buildUi())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    // ---------------------------------------------------------------- settings

    /** Master switch ("Mirror notifications"). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); recheck() }

    private val disabledApps: Set<String> get() = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty()

    fun setAppMirrored(pkg: String, mirrored: Boolean) {
        val now = disabledApps.toMutableSet().apply { if (mirrored) remove(pkg) else add(pkg) }
        prefs.edit().putStringSet(KEY_DISABLED, now).apply()
        _ui.value = buildUi()
        resync() // turning an app off removes its notifications from the Mac right away
    }

    /**
     * Asks the notification service itself. (The Settings value behind NotificationManagerCompat's
     * getEnabledListenerPackages lags the bind/unbind callbacks, which made grant/revoke look reversed.)
     */
    fun accessGranted(): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(NotifListener.component(context))
    }.getOrElse { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }

    override fun state(): CapabilityState = when {
        !enabled -> CapabilityState.DISABLED
        !accessGranted() -> CapabilityState.PERMISSION_MISSING
        else -> CapabilityState.AVAILABLE
    }

    /**
     * Re-evaluate permission/settings (app resumed, listener (un)bound, switch flipped). Tells the Mac when
     * the state changed, and asks the system to bind the listener if access is granted but it isn't bound.
     */
    fun recheck() {
        val s = state()
        val changed = lastState != null && s != lastState
        lastState = s
        if (s == CapabilityState.AVAILABLE && listener == null) NotifListener.rebind(context)
        _ui.value = buildUi()
        if (changed) onStateChanged()
    }

    // ---------------------------------------------------------------- Feature

    override suspend fun onSessionStarted(link: FeatureLink) {
        lastState = state()
        val out = object : NotificationMirror.Out {
            override fun <P : MessagePayload> send(mt: MessageType<P>, payload: P) = link.send(mt, payload)
        }
        mirror.start(out, listener?.snapshot(reader), disabledApps)
    }

    override fun onSessionEnded() = mirror.stop()

    override suspend fun onMessage(envelope: Envelope) {
        if (envelope.type != Types.NOTIF_DISMISS.type) return
        val key = try { envelope.decode(Types.NOTIF_DISMISS).key } catch (_: ProtocolException) { return }
        Log.i(NotifListener.TAG, "dismiss from Mac")
        listener?.dismiss(key) // the removal then flows back as notif.removed
    }

    // ---------------------------------------------------------------- listener events (main thread)

    internal fun listenerConnected(l: NotifListener) {
        listener = l
        recheckSoon()
        mirror.sync(l.snapshot(reader), disabledApps)
    }

    internal fun listenerDisconnected(l: NotifListener) {
        if (listener === l) listener = null
        recheckSoon()
    }

    /** Now, and once more shortly after, in case the system finishes updating access after the callback. */
    private fun recheckSoon() {
        recheck()
        main.postDelayed({ recheck() }, 1_000)
    }

    internal fun posted(l: NotifListener, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?) {
        if (listener == null) listener = l
        if (!enabled) return
        val c = runCatching { reader.read(sbn, ranking) }.getOrElse { Log.w(NotifListener.TAG, "read failed", it); return }
        rememberApp(c)
        mirror.posted(c, disabledApps)
    }

    internal fun removed(key: String) = mirror.removed(key)

    private fun resync() {
        val l = listener ?: return
        mirror.sync(l.snapshot(reader), disabledApps)
    }

    // ---------------------------------------------------------------- app list for the filter UI

    /** Apps that posted a mirrorable notification are offered in the per-app filter. */
    private fun rememberApp(c: Candidate) {
        if (NotificationFilter.skipReason(c, context.packageName, emptySet()) != null) return
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        if (seen.any { it.substringBefore('\t') == c.pkg }) return
        prefs.edit().putStringSet(KEY_SEEN, seen + "${c.pkg}\t${c.app}").apply()
        _ui.value = buildUi()
    }

    private fun buildUi(): Ui {
        val off = disabledApps
        val apps = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
            .map { it.substringBefore('\t') to it.substringAfter('\t') }
            .map { (pkg, label) -> AppEntry(pkg, label, pkg !in off) }
            .sortedBy { it.label.lowercase() }
        return Ui(enabled, accessGranted(), listener != null, apps)
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DISABLED = "disabledApps"
        private const val KEY_SEEN = "seenApps"
    }
}
