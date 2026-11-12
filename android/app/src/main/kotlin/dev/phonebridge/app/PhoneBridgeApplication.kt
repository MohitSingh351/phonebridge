package dev.phonebridge.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.phonebridge.clipboard.ClipboardFeature
import dev.phonebridge.clipboard.ClipboardHost
import dev.phonebridge.security.PairingStore
import dev.phonebridge.transport.ConnectionManager
import dev.phonebridge.transport.KeystoreConnector
import dev.phonebridge.transport.NsdMacLocator
import dev.phonebridge.transport.PairingController
import dev.phonebridge.transport.PhoneInfo
import dev.phonebridge.transport.lanAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** User-controlled switches. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("pb.settings", Context.MODE_PRIVATE)

    /** False after the user taps "Disconnect" in the notification; true again when they reconnect. */
    var autoConnect: Boolean
        get() = prefs.getBoolean("autoConnect", true)
        set(v) { prefs.edit().putBoolean("autoConnect", v).apply() }

    /** Show the text of items copied from the Mac in the little "Copied from Mac" notification. */
    var clipboardPreview: Boolean
        get() = prefs.getBoolean("clipboardPreview", true)
        set(v) { prefs.edit().putBoolean("clipboardPreview", v).apply() }
}

/**
 * Hand-written dependency graph (instead of Hilt — see the plan's decision log). Features are registered
 * here as they arrive, in one list, which is what DI multibinding would give us.
 */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = PairingStore(app)
    val settings = AppSettings(app)

    private val phoneInfo: () -> PhoneInfo = {
        PhoneInfo(
            deviceId = store.phoneDeviceId,
            deviceName = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0",
        )
    }

    // ---- Features: register new ones here (order doesn't matter; each is independent).
    val clipboard = ClipboardFeature(app, store.phoneDeviceId, showPreview = { settings.clipboardPreview })
    private val features = listOf(clipboard)

    val connection = ConnectionManager(
        store, phoneInfo, scope,
        connector = KeystoreConnector(),
        locator = NsdMacLocator(app),
        online = lanAvailability(app, scope),
        features = features,
    )

    val pairing = PairingController(store, phoneInfo, scope, onPaired = {
        settings.autoConnect = true
        BridgeService.start(app)
        Watchdog.schedule(app)
    })

    fun shouldRun() = store.pairedMac() != null && settings.autoConnect && LocalNetworkPermission.granted(app)

    /** Start (or resume) keeping the link alive. Safe to call repeatedly. */
    fun ensureRunning() {
        if (!shouldRun()) return
        if (!BridgeService.running) BridgeService.start(app)
        connection.start()
        connection.retryNow()
    }

    fun reconnect() {
        settings.autoConnect = true
        ensureRunning()
    }

    fun unpair() {
        connection.unpair()
        BridgeService.stop(app)
        Watchdog.cancel(app)
    }
}

class PhoneBridgeApplication : Application(), ClipboardHost {
    lateinit var graph: AppGraph
        private set

    override val clipboard: ClipboardFeature get() = graph.clipboard

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        BridgeService.ensureChannel(this)
        if (graph.shouldRun()) Watchdog.schedule(this)
        // Opening the app is always allowed to start the foreground service, and resets any backoff.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.ensureRunning()
        })
    }
}
