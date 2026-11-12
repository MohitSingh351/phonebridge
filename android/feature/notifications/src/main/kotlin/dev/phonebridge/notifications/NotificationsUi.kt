package dev.phonebridge.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Home-screen section: master switch, access status / how to grant it, per-app filter. */
@Composable
fun NotificationsSection(feature: NotificationsFeature) {
    val ui by feature.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showApps by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Switch) { feature.enabled = !ui.enabled },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Notifications on Mac", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Switch(checked = ui.enabled, onCheckedChange = null)
        }
        when {
            !ui.enabled -> Text("Off. Your Mac won't show this phone's notifications.",
                style = MaterialTheme.typography.bodySmall, color = muted)
            !ui.accessGranted -> {
                Text("Allow notification access so your Mac can show your phone's notifications. " +
                    "Dismissing one on either device clears it on both.",
                    style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { openAccessSettings(context) }) { Text("Allow notification access") }
                Text("Switch greyed out? Open App info → ⋮ menu → Allow restricted settings, then try again. " +
                    "(Android does this for apps not installed from the Play Store.)",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                TextButton(onClick = { openAppInfo(context) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Open App info")
                }
            }
            else -> {
                Text(
                    (if (ui.listening) "On. " else "Starting… ") +
                        "Ongoing, media and minimized notifications aren't sent. Android hides one-time codes " +
                        "and other sensitive content from companion apps, so those show as hidden on the Mac.",
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
                if (ui.apps.isNotEmpty()) {
                    TextButton(onClick = { showApps = !showApps }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(if (showApps) "Hide app list" else "Choose apps (${ui.apps.count { it.mirrored }} of ${ui.apps.size} on)")
                    }
                }
                if (showApps) {
                    ui.apps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Switch) { feature.setAppMirrored(app.pkg, !app.mirrored) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = app.mirrored, onCheckedChange = null)
                        }
                    }
                }
            }
        }
    }
}

private fun openAccessSettings(context: Context) {
    val detail = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotifListener.component(context).flattenToString())
    } else null
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    for (i in listOfNotNull(detail, list)) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
