package dev.phonebridge.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) adds a runtime permission for local-network access. We target API 36, where it may
 * not be enforced yet, but we request it whenever the OS defines it — so raising targetSdk later is a no-op.
 */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    fun isDefined(): Boolean = Build.VERSION.SDK_INT >= 37

    fun granted(context: Context): Boolean =
        !isDefined() || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}
