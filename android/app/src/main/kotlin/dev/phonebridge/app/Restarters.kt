package dev.phonebridge.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Restarts the connection service after a reboot or an app update. BOOT_COMPLETED may start a
 * `connectedDevice` foreground service (Android 15's boot restrictions cover other types only).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val graph = (context.applicationContext as PhoneBridgeApplication).graph
                if (graph.shouldRun()) BridgeService.start(context)
                Watchdog.schedule(context)
            }
        }
    }
}

/**
 * Safety net: every ~15 minutes, if PhoneBridge should be running but the service is gone (killed by an
 * aggressive battery manager), bring it back. Android allows this only with an exemption (battery
 * optimization off, or app recently visible); otherwise it quietly retries next time.
 */
class Watchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PhoneBridgeApplication).graph
        if (graph.shouldRun() && !BridgeService.running) BridgeService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "phonebridge-watchdog"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<Watchdog>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
