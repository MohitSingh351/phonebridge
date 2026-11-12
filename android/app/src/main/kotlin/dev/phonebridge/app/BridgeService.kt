package dev.phonebridge.app

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.phonebridge.transport.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the link to the Mac alive while the app is in the background: a `connectedDevice` foreground
 * service with a quiet, persistent notification. START_STICKY so Android restarts it if it kills the
 * process; boot receiver and watchdog cover the remaining cases. Never relies on battery-optimization
 * exemption, though it helps when granted.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph get() = (application as PhoneBridgeApplication).graph

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            graph.connection.retryNow() // screen on / unlocked: reconnect now instead of waiting out a backoff
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannel(this)
        goForeground(graph.connection.state.value)
        scope.launch { graph.connection.state.collect { Log.i(TAG, "link: $it"); updateNotification(it) } }
        ContextCompat.registerReceiver(
            this, wakeReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            graph.settings.autoConnect = false
            graph.connection.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (graph.store.pairedMac() == null || !graph.settings.autoConnect) {
            stopSelf()
            return START_NOT_STICKY
        }
        graph.connection.start()
        graph.connection.retryNow()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(wakeReceiver) }
        scope.cancel()
        if (!graph.settings.autoConnect || graph.store.pairedMac() == null) graph.connection.stop()
        super.onDestroy()
    }

    private fun goForeground(state: LinkState) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(state), type)
    }

    private fun updateNotification(state: LinkState) {
        if (state is LinkState.NotPaired || state is LinkState.Removed) {
            // Nothing to keep alive. Leave a one-off notification for "removed" so the user knows why.
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: LinkState): Notification {
        val (title, text) = when (state) {
            is LinkState.Connected -> "Connected to ${state.macName}" to "PhoneBridge is linked to your Mac"
            is LinkState.Connecting -> "Connecting to ${state.macName}…" to "Looking for your Mac on Wi-Fi"
            is LinkState.Waiting -> "${state.macName} not reachable" to "Will reconnect automatically"
            is LinkState.NoNetwork -> "Waiting for Wi-Fi" to "Reconnects to ${state.macName} when you're back on Wi-Fi"
            else -> "PhoneBridge" to "Starting…"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val sendClip = PendingIntent.getActivity(
            this, 2, dev.phonebridge.clipboard.ClipboardSendActivity.intent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (state is LinkState.Connected || state is LinkState.Waiting) addAction(0, "Send clipboard", sendClip) }
            .addAction(0, "Disconnect", disconnect)
            .build()
    }

    companion object {
        private const val TAG = "PhoneBridge"
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "dev.phonebridge.action.DISCONNECT"

        @Volatile var running = false
            private set

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Connection to Mac", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows that PhoneBridge is keeping your phone linked to your Mac."
                        setShowBadge(false)
                    },
                )
            }
        }

        /**
         * Starts the service. Returns false if Android refused (background start without an exemption);
         * the next app open, boot, or watchdog run with an exemption will try again.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
            true
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.i(TAG, "foreground service start not allowed from background; will retry later")
            } else Log.w(TAG, "could not start service", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
