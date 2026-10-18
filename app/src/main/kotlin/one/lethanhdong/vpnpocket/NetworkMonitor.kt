package one.lethanhdong.vpnpocket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Monitors network changes via Android's ConnectivityManager and notifies
 * the Go tunnel layer. Follows the tailscale-android pattern
 * (NetworkChangeCallback.kt): Kotlin owns network monitoring, Go receives
 * callbacks — Go never touches netlink sockets directly.
 *
 * The NOT_VPN capability filter avoids routing loops (our own TUN shows up
 * as a VPN network; we must not treat it as the uplink).
 */
object NetworkMonitor {
    private const val TAG = "NetworkMonitor"

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start monitoring. [onChange] is invoked with true when a usable
     * non-VPN network becomes available, false when the last one is lost.
     * Safe to call multiple times; previous callback is unregistered first.
     */
    fun start(context: Context, onChange: (Boolean) -> Unit) {
        stop(context)
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: run {
                Log.w(TAG, "ConnectivityManager unavailable")
                return
            }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "network available: $network")
                onChange(true)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "network lost: $network")
                // Only report loss; if another network is still up the
                // next onAvailable (or activeNetwork check) will correct it.
                onChange(cm.activeNetwork == null)
            }
        }
        callback = cb
        cm.registerNetworkCallback(req, cb)
        Log.d(TAG, "monitoring started")
    }

    fun stop(context: Context) {
        val cb = callback ?: return
        callback = null
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed: ${e.message}")
        }
        Log.d(TAG, "monitoring stopped")
    }

    /** Synchronous check: is there a usable non-VPN network right now? */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
