package dev.phonebridge.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import dev.phonebridge.protocol.PBConstants
import dev.phonebridge.security.DeviceIdentity
import dev.phonebridge.security.PairedMac
import dev.phonebridge.security.PinnedTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Production connector: Keystore device key + TLS pinned to the Mac's key. */
class KeystoreConnector : Connector {
    override fun signer(mac: PairedMac): Signer? {
        val identity = DeviceIdentity.load(mac.keyAlias) ?: return null
        return object : Signer {
            override val fingerprint = identity.fingerprint
            override fun sign(message: ByteArray) = identity.sign(message)
        }
    }

    override fun open(host: String, port: Int, mac: PairedMac, client: SessionClient, scope: CoroutineScope,
                      onEvent: suspend (ClientEvent) -> Unit): WsConnection {
        val identity = requireNotNull(DeviceIdentity.load(mac.keyAlias))
        return WsConnection(host, port, PinnedTls(identity, mac.fingerprint), client, scope, onEvent)
    }
}

/**
 * True while a Wi-Fi or Ethernet network is up. Deliberately does not require internet access:
 * a home LAN without internet is fine for PhoneBridge.
 */
fun lanAvailability(context: Context, scope: CoroutineScope): StateFlow<Boolean> {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    return callbackFlow {
        val live = mutableSetOf<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { synchronized(live) { live += network; trySend(true) } }
            override fun onLost(network: Network) { synchronized(live) { live -= network; trySend(live.isNotEmpty()) } }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(req, cb)
        trySend(cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        })
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, true)
}

/**
 * Finds the paired Mac via DNS-SD (`_phonebridge._tcp`, TXT `id=<macId>`). Holds a multicast lock only
 * while searching, to save battery.
 */
class NsdMacLocator(private val context: Context, private val timeoutMs: Long = 6_000) : MacLocator {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    override suspend fun locate(macId: UUID): MacLocator.Address? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("phonebridge-discovery").apply { setReferenceCounted(false); acquire() }
        try {
            return withTimeoutOrNull(timeoutMs) { discover(macId) }
        } finally {
            lock.release()
        }
    }

    private suspend fun discover(macId: UUID): MacLocator.Address? = suspendCancellableCoroutine { cont ->
        val type = PBConstants.SERVICE_TYPE + "."
        var done = false
        lateinit var listener: NsdManager.DiscoveryListener
        fun finish(addr: MacLocator.Address?) {
            synchronized(this) { if (done) return; done = true }
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (cont.isActive) cont.resume(addr)
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { resolve(info, macId) { it?.let(::finish) } }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private fun resolve(found: NsdServiceInfo, macId: UUID, onResult: (MacLocator.Address?) -> Unit) {
        fun check(info: NsdServiceInfo): MacLocator.Address? {
            val id = info.attributes["id"]?.toString(Charsets.UTF_8)
            if (id != macId.toString()) return null
            val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is Inet4Address }
                       else @Suppress("DEPRECATION") info.host
            return host?.hostAddress?.let { MacLocator.Address(it, info.port) }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                    onResult(check(info))
                }
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = onResult(null)
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            nsd.registerServiceInfoCallback(found, executor, cb)
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(found, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = onResult(check(info))
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = onResult(null)
            })
        }
    }
}
