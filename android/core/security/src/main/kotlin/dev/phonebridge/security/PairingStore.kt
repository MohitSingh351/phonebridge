package dev.phonebridge.security

import android.content.Context
import android.content.SharedPreferences
import dev.phonebridge.protocol.Hex
import dev.phonebridge.protocol.Uuids
import java.util.UUID

/**
 * The paired Mac. None of this is secret (the secret is the Keystore key), but its integrity matters:
 * `fingerprintHex` is the pin that authenticates the Mac.
 */
data class PairedMac(
    val macId: UUID,
    val name: String,
    val fingerprintHex: String,
    val host: String,
    val port: Int,
    val keyAlias: String,
) {
    val fingerprint: ByteArray get() = Hex.decode(fingerprintHex)
}

/** App-private storage for the phone's device id and its paired Mac (one Mac in v1). */
class PairingStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pb.pairing", Context.MODE_PRIVATE)

    /** Stable per-install id, sent as `deviceId`. A reinstall gets a new id and must re-pair. */
    val phoneDeviceId: UUID
        get() = prefs.getString(KEY_PHONE_ID, null)?.let(Uuids::parse) ?: UUID.randomUUID().also {
            prefs.edit().putString(KEY_PHONE_ID, it.toString()).apply()
        }

    fun pairedMac(): PairedMac? {
        val id = prefs.getString("mac.id", null)?.let(Uuids::parse) ?: return null
        return PairedMac(
            macId = id,
            name = prefs.getString("mac.name", null) ?: "Mac",
            fingerprintHex = prefs.getString("mac.fp", null) ?: return null,
            host = prefs.getString("mac.host", null) ?: return null,
            port = prefs.getInt("mac.port", 0).takeIf { it > 0 } ?: return null,
            keyAlias = prefs.getString("mac.alias", null) ?: return null,
        )
    }

    fun save(mac: PairedMac) {
        prefs.edit()
            .putString("mac.id", mac.macId.toString())
            .putString("mac.name", mac.name)
            .putString("mac.fp", mac.fingerprintHex)
            .putString("mac.host", mac.host)
            .putInt("mac.port", mac.port)
            .putString("mac.alias", mac.keyAlias)
            .commit()
    }

    /** Remember where the Mac was last reachable (fast reconnect before discovery). */
    fun updateAddress(host: String, port: Int) {
        prefs.edit().putString("mac.host", host).putInt("mac.port", port).apply()
    }

    /** Forget the Mac and destroy the device key. */
    fun clear() {
        pairedMac()?.let { DeviceIdentity.delete(it.keyAlias) }
        prefs.edit().remove("mac.id").remove("mac.name").remove("mac.fp").remove("mac.host")
            .remove("mac.port").remove("mac.alias").commit()
    }

    private companion object { const val KEY_PHONE_ID = "phone.id" }
}
