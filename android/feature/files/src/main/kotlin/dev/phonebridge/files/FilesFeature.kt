package dev.phonebridge.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.phonebridge.protocol.Envelope
import dev.phonebridge.protocol.Features
import dev.phonebridge.protocol.MessagePayload
import dev.phonebridge.protocol.MessageType
import dev.phonebridge.transport.Feature
import dev.phonebridge.transport.FeatureLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Implemented by the Application so share/picker entry points can reach the feature. */
interface FilesHost {
    val files: FilesFeature
}

/**
 * File transfer, phone side. Owns a [TransferEngine] confined to one IO thread (never the session
 * thread, so long hashes don't delay heartbeats), plus the progress notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesFeature(private val context: Context, private val scope: CoroutineScope, private val openApp: () -> Intent) : Feature {
    override val id = Features.FILES

    private val io = AndroidFileIO(context)
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private val _transfers = MutableStateFlow<List<TransferRecord>>(emptyList())
    val transfers: StateFlow<List<TransferRecord>> = _transfers.asStateFlow()
    private val engine = TransferEngine(io, JsonTransferStore(context), onChange = { list ->
        _transfers.value = list.sortedByDescending { it.createdAt }
        updateNotification(list)
        // Release share-sheet descriptors once their transfer is over.
        list.filter { it.direction == Direction.OUT && it.state.isFinal }.forEach { r -> r.ref?.let(io::release) }
    }, log = { android.util.Log.i("PhoneBridge.files", it) })
    private var ticker: Job? = null

    init { _transfers.value = engine.all.sortedByDescending { it.createdAt } }

    // ---------------------------------------------------------------- Feature

    override suspend fun onSessionStarted(link: FeatureLink) = on {
        engine.onSessionStarted(object : TransferWire {
            override fun <P : MessagePayload> json(mt: MessageType<P>, payload: P) { link.send(mt, payload) }
            override fun binary(frame: ByteArray) { link.sendBinary(frame) }
        })
        ticker?.cancel()
        ticker = scope.launch(thread + guard) {
            while (isActive) { delay(1000); runCatching { engine.tick() }.onFailure { android.util.Log.e("PhoneBridge.files", "tick", it) } }
        }
    }

    override suspend fun onMessage(envelope: Envelope) = on { engine.onMessage(envelope) }

    override suspend fun onBinary(frame: ByteArray) = on { engine.onBinary(frame) }

    override fun onSessionEnded() {
        ticker?.cancel(); ticker = null
        scope.launch(thread + guard) { engine.onSessionEnded() }
    }

    // ---------------------------------------------------------------- user actions

    /**
     * Send files from the share sheet. MUST be called from the share activity before it finishes: the
     * temporary read grant is used right here to open each file, then hashing continues in the background.
     */
    fun sendShared(uris: List<Uri>): Int {
        val refs = uris.mapNotNull { io.hold(it) }
        sendRefs(refs)
        return refs.size
    }

    /** Send files chosen in the share sheet or picker. Hashes each file first (streams it once). */
    fun send(uris: List<Uri>) {
        // Picker URIs: keep access across app restarts when allowed (so transfers can resume later).
        for (uri in uris) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        sendRefs(uris.map { it.toString() })
    }

    private fun sendRefs(refs: List<String>) {
        scope.launch(thread + guard) {
            for (ref in refs) {
                val meta = runCatching { io.describe(ref) }.getOrNull() ?: continue
                val hash = runCatching { io.sha256(ref) }.getOrNull() ?: continue
                engine.send(ref, meta.name, meta.size, meta.mime, hash)
            }
        }
    }

    /** Last line of defence: a bug in a transfer must never take the whole app (and the link) down. */
    private val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.e("PhoneBridge.files", "transfer error", e)
    }

    fun pause(id: String) = fire { engine.pause(id) }
    fun resume(id: String) = fire { engine.resume(id) }
    fun cancel(id: String) = fire { engine.cancel(id) }
    fun retry(id: String) = fire { engine.retry(id) }
    fun clearHistory() = fire { engine.clearHistory() }

    /** Open a received file. */
    fun openIntent(r: TransferRecord): Intent? {
        if (r.direction != Direction.IN || r.state != TransferState.DONE) return null
        val uri = Uri.parse(r.ref ?: return null)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun on(block: () -> Unit) = withContext(thread) {
        runCatching(block).onFailure { android.util.Log.e("PhoneBridge.files", "transfer error", it) }
        Unit
    }
    private fun fire(block: () -> Unit) { scope.launch(thread + guard) { block() } }

    // ---------------------------------------------------------------- progress notification

    private var lastNotified = 0L

    private fun updateNotification(list: List<TransferRecord>) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val active = list.filter { it.state == TransferState.ACTIVE || it.state == TransferState.VERIFYING }
        if (active.isEmpty()) { nm.cancel(NOTIFICATION_ID); return }
        val now = System.currentTimeMillis()
        if (now - lastNotified < 500) return
        lastNotified = now
        ensureChannel()
        val total = active.sumOf { it.size }.coerceAtLeast(1)
        val done = active.sumOf { it.transferred }
        val speed = active.sumOf { it.bytesPerSec }
        val title = if (active.size == 1) {
            (if (active[0].direction == Direction.OUT) "Sending " else "Receiving ") + active[0].name
        } else "${active.size} transfers"
        val eta = if (speed > 0) " · ${formatDuration((total - done) / speed)} left" else ""
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText("${formatBytes(done)} of ${formatBytes(total)}$eta")
            .setProgress(1000, (done * 1000 / total).toInt(), active.any { it.state == TransferState.VERIFYING } && done >= total)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 10, openApp(), PendingIntent.FLAG_IMMUTABLE))
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of files being sent to or received from your Mac."
                setShowBadge(false)
            })
        }
    }

    companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 3
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun formatDuration(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}
