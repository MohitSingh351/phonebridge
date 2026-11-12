package dev.phonebridge.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.transport.PairingController
import dev.phonebridge.transport.PairingQr
import dev.phonebridge.transport.PairingState

/** Full pairing flow: camera permission → scan → compare code → done. */
@Composable
fun PairingScreen(controller: PairingController, modifier: Modifier = Modifier, onFinished: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val s = state) {
            PairingState.Idle -> ScanStep(onScanned = controller::start)
            is PairingState.Connecting -> Busy("Connecting to ${s.macName}…")
            is PairingState.ConfirmCode -> ConfirmStep(s, onCancel = controller::cancel)
            is PairingState.Paired -> Result(
                ok = true, title = "Paired with ${s.macName}",
                body = "Your phone will now connect to this Mac automatically.",
                action = "Done", onAction = { controller.reset(); onFinished() },
            )
            is PairingState.Failed -> Result(
                ok = false, title = "Pairing didn't complete", body = PairingController.describe(s.reason),
                action = "Try again", onAction = controller::reset,
            )
        }
    }
}

@Composable
private fun ScanStep(onScanned: (PairingQr) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it; denied = !it
    }
    var invalid by remember { mutableStateOf(false) }

    Text("Pair with your Mac", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        "On your Mac, click the PhoneBridge icon in the menu bar → “Pair a New Phone…”, then scan the code.",
        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))

    if (granted) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            QrScanner(Modifier.fillMaxSize()) { raw ->
                val qr = PairingQr.parse(raw)
                if (qr == null) { invalid = true; false } else { onScanned(qr); true }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (invalid) "That isn't a PhoneBridge pairing code." else "Point the camera at the QR code.",
            style = MaterialTheme.typography.bodySmall,
            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            "PhoneBridge needs the camera only to scan the pairing code. Nothing is recorded or stored.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (denied) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Open Settings") }
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
    }
}

@Composable
private fun ConfirmStep(s: PairingState.ConfirmCode, onCancel: () -> Unit) {
    Text("Confirm on your Mac", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(
        "Check that ${s.macName} shows this code, then click Allow on the Mac.",
        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
    Text(
        "${s.code.take(3)} ${s.code.takeLast(3)}",
        fontSize = 44.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { contentDescription = "Code " + s.code.toList().joinToString(" ") },
    )
    Spacer(Modifier.height(28.dp))
    CircularProgressIndicator(Modifier.size(28.dp))
    Spacer(Modifier.height(8.dp))
    Text("Waiting for the Mac…", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(text)
}

@Composable
private fun Result(ok: Boolean, title: String, body: String, action: String, onAction: () -> Unit) {
    Icon(
        if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, contentDescription = null,
        Modifier.size(72.dp), tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onAction) { Text(action) }
}

/** For callers that just want the auto-launch effect when pairing finishes. */
@Composable
fun OnPaired(controller: PairingController, block: () -> Unit) {
    val s by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(s) { if (s is PairingState.Paired) block() }
}
