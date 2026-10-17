package one.lethanhdong.vpnpocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * P2.1: VpnService + TUN (IPv4+IPv6, MTU 1400).
 * P2.3 sẽ gắn packet forwarder vào [onTunnelReady].
 *
 * Pattern học từ mổ ProtonVPN (~/workspace/your_files/protonvpn.md):
 * - setUnderlyingNetworks(null), setMetered(false) (API 29+)
 * - establish() bọc try/catch trả null thay vì crash
 * - START_STICKY chỉ khi đã connect thành công
 */
class PocketVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "one.lethanhdong.vpnpocket.CONNECT"
        const val ACTION_DISCONNECT = "one.lethanhdong.vpnpocket.DISCONNECT"
        private const val TAG = "PocketVpn"
        private const val CHANNEL_ID = "vpn_pocket_tunnel"
        private const val NOTIF_ID = 1

        // Địa chỉ TUN (private, tránh đụng dải phổ biến).
        private const val TUN_IPV4 = "10.8.0.2"
        private const val TUN_IPV6 = "fd00:8::2"
        // DNS "ảo": mọi query UDP/53 tới đây bị intercept, trả lời qua DoH (P2.4).
        private const val DNS_IPV4 = "10.8.0.1"
        // Spec §3.4.
        private const val MTU = 1400
    }

    private var tunFd: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var tunnelManager: TunnelManager? = null
    private var forwarder: TcpForwarder? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Chống spam toast: tối đa 1 lần / 60s.
    private val lastUdpToastAt = AtomicLong(0)
    // P2.6 watchdog: quét /proc mỗi 30s để protect DERP socket mới (reconnect).
    private val watchdog = object : Runnable {
        override fun run() {
            val n = tunnelManager?.protectScan() ?: 0
            if (n > 0) Log.i(TAG, "watchdog: protected $n new socket(s)")
            mainHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                if (running.compareAndSet(false, true)) {
                    if (startTunnel()) {
                        return START_STICKY
                    }
                    running.set(false)
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // User tắt VPN từ Settings hệ thống.
        Log.i(TAG, "onRevoke: VPN revoked by system")
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel(): Boolean {
        startForeground(NOTIF_ID, buildNotification("Đang kết nối…"))
        val fd = buildTun() ?: run {
            Log.e(TAG, "establish() failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return false
        }
        tunFd = fd
        // P2.2: nối tunnel nếu đã có serve address (QR scan ở P2.6).
        val tm = TunnelManager(this, this)
        tunnelManager = tm
        if (tm.hasServeAddr()) {
            try {
                tm.connect()
                updateNotification("Đã kết nối")
            } catch (e: Exception) {
                Log.e(TAG, "tunnel connect failed: ${e.message}")
                updateNotification("Lỗi kết nối: ${e.message}")
                // Vẫn giữ TUN mở để user thấy trạng thái; forwarder (P2.3) sẽ retry.
            }
        } else {
            updateNotification("Chưa quét mã QR")
        }
        Log.i(TAG, "TUN established (mtu=$MTU)")
        onTunnelReady(fd)
        return true
    }

    /**
     * Seam cho P2.3: packet forwarder đọc/ghi fd ở đây.
     */
    private fun onTunnelReady(fd: ParcelFileDescriptor) {
        val tm = tunnelManager ?: return
        // P2.4: DNS → DoH. P2.5: UDP khác → drop + toast (Mode A không có UDP).
        val dns = DnsHandler(tm) { pkt -> forwarder?.writeTun(pkt) }
        forwarder = TcpForwarder(fd, tm, onUdp = { pkt ->
            if (!dns.handleUdpPacket(pkt)) {
                Log.d(TAG, "UDP non-DNS dropped (${pkt.size} bytes)")
                notifyUdpDropped()
            }
        }).apply { start() }
        // P2.6: watchdog protect DERP socket mới.
        mainHandler.postDelayed(watchdog, 30_000)
    }

    /**
     * P2.5: Mode A = TCP-only (VM chặn UDP). Báo user 1 lần / 60s để khỏi spam,
     * vì app VoIP/game sẽ gửi UDP liên tục.
     */
    private fun notifyUdpDropped() {
        val now = System.currentTimeMillis()
        if (now - lastUdpToastAt.get() < 60_000) return
        lastUdpToastAt.set(now)
        mainHandler.post {
            Toast.makeText(
                this,
                "Chế độ này không hỗ trợ gọi thoại/game (chỉ TCP)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopTunnel() {
        if (!running.compareAndSet(true, false)) return
        mainHandler.removeCallbacks(watchdog)
        forwarder?.stop()
        forwarder = null
        tunnelManager?.disconnect()
        tunnelManager = null
        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close tun: ${e.message}")
        }
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "tunnel stopped")
    }

    private fun buildTun(): ParcelFileDescriptor? {
        return try {
            Builder().apply {
                setSession("VPN Pocket")
                setMtu(MTU)
                addAddress(TUN_IPV4, 32)
                addAddress(TUN_IPV6, 128)
                addRoute("0.0.0.0", 0)
                addRoute("::", 0)
                addDnsServer(DNS_IPV4)
                // Không route traffic VPN qua mạng underlying nào cụ thể
                // (pattern ProtonVPN: tránh loop khi đổi mạng).
                setUnderlyingNetworks(null)
                if (Build.VERSION.SDK_INT >= 29) setMetered(false)
            }.establish()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "establish: bad argument: ${e.message}")
            null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "establish: already established or not prepared: ${e.message}")
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "establish: no VPN permission: ${e.message}")
            null
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VPN Pocket",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Pocket")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
