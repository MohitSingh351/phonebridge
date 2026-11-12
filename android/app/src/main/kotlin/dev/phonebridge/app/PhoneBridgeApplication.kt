package dev.phonebridge.app

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.phonebridge.security.PairingStore
import dev.phonebridge.transport.ConnectionManager
import dev.phonebridge.transport.PairingController
import dev.phonebridge.transport.PhoneInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-written dependency graph (instead of Hilt — see the plan's decision log). Features are registered
 * here as they arrive, in one list, which is what DI multibinding would give us.
 */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = PairingStore(app)

    private val phoneInfo: () -> PhoneInfo = {
        PhoneInfo(
            deviceId = store.phoneDeviceId,
            deviceName = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfigCompat.versionName(app),
        )
    }

    val connection = ConnectionManager(store, phoneInfo, scope)
    val pairing = PairingController(store, phoneInfo, scope, onPaired = { connection.start() })
}

class PhoneBridgeApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Foreground-only connection for now; Phase 2 moves this into a foreground service.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (LocalNetworkPermission.granted(this@PhoneBridgeApplication)) {
                    graph.connection.start()
                    graph.connection.retryNow()
                }
            }
        })
    }
}

object BuildConfigCompat {
    fun versionName(app: Application): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"
}
