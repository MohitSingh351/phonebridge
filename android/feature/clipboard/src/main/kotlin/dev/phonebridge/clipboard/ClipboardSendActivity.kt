package dev.phonebridge.clipboard

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity behind every phone → Mac path. Android 10+ lets an app read the clipboard only while
 * it has window focus, so the tile and notification action open this for a fraction of a second.
 * Share-sheet text arrives in the intent instead and needs no clipboard read.
 */
class ClipboardSendActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            send(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
        // Otherwise wait for focus (onWindowFocusChanged) before touching the clipboard.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        send(text)
    }

    private fun send(text: String?) {
        if (handled) return
        handled = true
        val feature = (application as? ClipboardHost)?.clipboard
        val msg = when (feature?.sendFromUser(text)) {
            ClipboardFeature.SendResult.SENT -> "Sent to Mac clipboard"
            ClipboardFeature.SendResult.QUEUED -> "Mac not connected — will send when it is (within 5 min)"
            ClipboardFeature.SendResult.ALREADY_ON_MAC -> "Already on your Mac's clipboard"
            ClipboardFeature.SendResult.EMPTY -> "Clipboard is empty"
            ClipboardFeature.SendResult.TOO_LARGE -> "Too large for the clipboard — share it as a file instead"
            null -> "PhoneBridge isn't ready"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransitionCompat()
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() = overridePendingTransition(0, 0)

    companion object {
        const val ACTION_SEND_CLIPBOARD = "dev.phonebridge.action.SEND_CLIPBOARD"

        fun intent(context: Context) = Intent(context, ClipboardSendActivity::class.java)
            .setAction(ACTION_SEND_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
