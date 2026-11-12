package dev.phonebridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.pairing.PairingScreen
import dev.phonebridge.transport.LinkState
import dev.phonebridge.transport.PairingState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // mandatory for targetSdk 35+
        super.onCreate(savedInstanceState)
        val graph = (application as PhoneBridgeApplication).graph
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) {
                Scaffold { inner ->
                    App(graph, Modifier.padding(inner))
                }
            }
        }
    }
}

@Composable
private fun App(graph: AppGraph, modifier: Modifier) {
    val link by graph.connection.state.collectAsStateWithLifecycle()
    val pairing by graph.pairing.state.collectAsStateWithLifecycle()
    var wantsPairing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var netGranted by remember { mutableStateOf(LocalNetworkPermission.granted(context)) }
    val netLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        netGranted = it
        if (it) graph.ensureRunning()
    }

    when {
        !netGranted -> LocalNetworkRationale(modifier) { netLauncher.launch(LocalNetworkPermission.NAME) }
        link is LinkState.NotPaired || wantsPairing || pairing !is PairingState.Idle ->
            PairingScreen(graph.pairing, modifier) { wantsPairing = false }
        else -> Home(
            link, modifier, graph.files,
            onRetry = graph.connection::retryNow,
            onConnect = graph::reconnect,
            onUnpair = graph::unpair,
            onPairAgain = { graph.unpair(); wantsPairing = true },
        )
    }
}

@Composable
private fun LocalNetworkRationale(modifier: Modifier, onAllow: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("Find your Mac on Wi-Fi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "PhoneBridge talks to your Mac directly over your local network — nothing goes through the internet. " +
                "Android asks you to allow local network access for this.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAllow) { Text("Continue") }
    }
}

@Composable
private fun Home(
    link: LinkState, modifier: Modifier, files: dev.phonebridge.files.FilesFeature,
    onRetry: () -> Unit, onConnect: () -> Unit, onUnpair: () -> Unit, onPairAgain: () -> Unit,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PhoneBridge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Laptop, contentDescription = null, Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val (name, line, color) = describe(link)
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(6.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (link is LinkState.Connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        when (link) {
            is LinkState.Removed -> {
                Text("${link.macName} removed this phone. Pair again to reconnect.", color = MaterialTheme.colorScheme.error)
                Button(onClick = onPairAgain) { Text("Pair again") }
            }
            is LinkState.Waiting -> OutlinedButton(onClick = onRetry) { Text("Retry now") }
            LinkState.Stopped -> Button(onClick = onConnect) { Text("Connect") }
            else -> Unit
        }

        SetupCards()

        if (link !is LinkState.Removed) {
            dev.phonebridge.files.FilesSection(files, connected = link is LinkState.Connected)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Clipboard, files, notifications and Find My Phone arrive in the next updates.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { confirmUnpair = true }) { Text("Unpair this phone") }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair?") },
            text = { Text("This phone's key is deleted and the Mac forgets it. You can pair again any time.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; onUnpair() }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

private fun describe(link: LinkState): Triple<String, String, Color> = when (link) {
    is LinkState.Connected -> Triple(link.macName, "Connected", Color(0xFF2E7D32))
    is LinkState.Connecting -> Triple(link.macName, "Connecting…", Color(0xFFF9A825))
    is LinkState.Waiting -> Triple(link.macName, link.reason.replaceFirstChar { it.uppercase() } + " · retrying", Color(0xFFF9A825))
    is LinkState.Removed -> Triple(link.macName, "Removed by the Mac", Color(0xFFC62828))
    is LinkState.NoNetwork -> Triple(link.macName, "Waiting for Wi-Fi", Color.Gray)
    LinkState.Stopped -> Triple("Mac", "Disconnected", Color.Gray)
    LinkState.NotPaired -> Triple("No Mac", "Not paired", Color.Gray)
}
