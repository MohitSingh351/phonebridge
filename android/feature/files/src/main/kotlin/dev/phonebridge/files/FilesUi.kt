package dev.phonebridge.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** "Send files" button + active transfers + recent history, for the home screen. */
@Composable
fun FilesSection(feature: FilesFeature, connected: Boolean) {
    val transfers by feature.transfers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            feature.send(uris)
            if (!connected) Toast.makeText(context, "Will send when your Mac is connected", Toast.LENGTH_SHORT).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send files")
            }
        }
        val active = transfers.filter { !it.state.isFinal }
        val history = transfers.filter { it.state.isFinal }.take(20)
        if (transfers.isEmpty()) {
            Text("Share any file to “PhoneBridge”, or tap Send files. Files from your Mac land in Downloads/PhoneBridge.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        active.forEach { TransferRow(it, feature) }
        if (history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = feature::clearHistory) { Text("Clear") }
            }
            history.forEach { TransferRow(it, feature) }
        }
    }
}

@Composable
private fun TransferRow(r: TransferRecord, feature: FilesFeature) {
    val context = LocalContext.current
    val open = feature.openIntent(r)
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = open != null) { runCatching { context.startActivity(open) } }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (r.direction == Direction.OUT) Icons.Filled.CallMade else Icons.Filled.CallReceived,
                contentDescription = if (r.direction == Direction.OUT) "Sent" else "Received", Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.savedName ?: r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(statusLine(r), style = MaterialTheme.typography.bodySmall,
                    color = if (r.state == TransferState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (r.state) {
                TransferState.ACTIVE, TransferState.WAITING ->
                    IconButton(onClick = { feature.pause(r.id) }) { Icon(Icons.Filled.Pause, "Pause") }
                TransferState.PAUSED ->
                    IconButton(onClick = { feature.resume(r.id) }) { Icon(Icons.Filled.PlayArrow, "Resume") }
                TransferState.FAILED -> if (r.direction == Direction.OUT) IconButton(onClick = { feature.retry(r.id) }) { Icon(Icons.Filled.Refresh, "Retry") }
                else -> Unit
            }
            if (!r.state.isFinal) IconButton(onClick = { feature.cancel(r.id) }) { Icon(Icons.Filled.Close, "Cancel") }
        }
        if (!r.state.isFinal && r.size > 0) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { (r.transferred.toFloat() / r.size).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        }
    }
}

private fun statusLine(r: TransferRecord): String = when (r.state) {
    TransferState.WAITING -> "Waiting for Mac · ${formatBytes(r.size)}"
    TransferState.ACTIVE -> buildString {
        append("${formatBytes(r.transferred)} of ${formatBytes(r.size)}")
        if (r.bytesPerSec > 0) append(" · ${formatBytes(r.bytesPerSec)}/s · ${formatDuration((r.size - r.transferred) / r.bytesPerSec)} left")
    }
    TransferState.PAUSED -> "Paused${if (r.pausedBy == PausedBy.REMOTE) " on Mac" else ""} · ${formatBytes(r.transferred)} of ${formatBytes(r.size)}"
    TransferState.VERIFYING -> "Checking…"
    TransferState.DONE -> (if (r.direction == Direction.OUT) "Sent" else "Saved to Downloads/PhoneBridge") + " · ${formatBytes(r.size)}"
    TransferState.FAILED -> "Failed: " + when (r.error) {
        "insufficient_space" -> "not enough space"
        "hash_mismatch" -> "file changed or corrupted — retry"
        "transfer_failed" -> "connection too unreliable — retry"
        "source_unavailable" -> "file no longer accessible"
        else -> r.error ?: "unknown"
    }
    TransferState.CANCELLED -> "Cancelled"
}

/** Share-sheet target: "Send to Mac" for any file(s). */
class ShareFilesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra()
            else -> emptyList()
        }
        val host = application as? FilesHost
        if (uris.isEmpty() || host == null) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
        } else {
            val n = host.files.sendShared(uris) // opens the files now, while the share grant is valid
            Toast.makeText(this, when {
                n == 0 -> "Couldn't open the shared file"
                n == 1 -> "Sending to Mac…"
                else -> "Sending $n files to Mac…"
            }, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(): Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(): List<Uri> = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
