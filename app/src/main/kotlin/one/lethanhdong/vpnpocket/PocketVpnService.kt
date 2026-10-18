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
 * VpnService + TUN (IPv4+IPv6, MTU 1400) + TCP forwarder + DNS DoH + watchdog.
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
        // State machine học ProtonVPN (VpnState): service broadcast từng giai đoạn,
        // UI chỉ nghe và hiển thị — không tự đoán trạng thái.
        // (Bỏ ScanningPorts của họ vì mình dùng DERP cố định 443, không scan port.)
        const val ACTION_TUNNEL_CHECKING = "one.lethanhdong.vpnpocket.TUNNEL_CHECKING" // đang kiểm tra máy chủ
        const val ACTION_TUNNEL_CONNECTING = "one.lethanhdong.vpnpocket.TUNNEL_CONNECTING" // đang nối tunnel
        const val ACTION_TUNNEL_VERIFYING = "one.lethanhdong.vpnpocket.TUNNEL_VERIFYING" // đang xác minh qua tunnel
        const val ACTION_TUNNEL_UP = "one.lethanhdong.vpnpocket.TUNNEL_UP" // đã kết nối (kèm IP)
        const val ACTION_TUNNEL_DOWN = "one.lethanhdong.vpnpocket.TUNNEL_DOWN" // lỗi/ngắt (kèm lý do)
        const val ACTION_TUNNEL_RECONNECTING = "one.lethanhdong.vpnpocket.TUNNEL_RECONNECTING" // watchdog nối lại
        const val EXTRA_IP = "ip"
        const val EXTRA_REASON = "reason"
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
    // P2.6 watchdog: mỗi 30s protect DERP socket mới (reconnect) + tự nối lại nếu rớt.
    private val watchdog = object : Runnable {
        override fun run() {
            val tm = tunnelManager
            if (tm != null) {
                // Tự reconnect nếu tunnel chưa lên (vd lần đầu fail do mất mạng).
                if (tm.getTunnel() == null && tm.hasServeAddr()) {
                    broadcast(ACTION_TUNNEL_RECONNECTING)
                    updateNotification("Đang kết nối lại…")
                    try {
                        tm.connect()
                        // Verify lại sau reconnect (giống lần đầu).
                        verifyAndBroadcast(tm)
                        Log.i(TAG, "watchdog: reconnected")
                    } catch (e: Exception) {
                        Log.w(TAG, "watchdog reconnect failed: ${e.message}")
                    }
                }
                val n = tm.protectScan()
                if (n > 0) Log.i(TAG, "watchdog: protected $n new socket(s)")
            }
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

    private fun broadcast(action: String, ip: String? = null, reason: String? = null) {
        sendBroadcast(Intent(action).apply {
            if (ip != null) putExtra(EXTRA_IP, ip)
            if (reason != null) putExtra(EXTRA_REASON, reason)
            setPackage(packageName)
        })
    }

    private fun startTunnel(): Boolean {
        startForeground(NOTIF_ID, buildNotification("Đang kết nối…"))
        // THỨ TỰ BẮT BUỘC: connect tunnel TRƯỚC khi establish() TUN.
        // Socket DERP tạo trong Ping() phải đi đường mạng thường. Nếu establish()
        // trước, packet DERP lọt vào TUN (chưa có forwarder) → blackhole → Ping timeout.
        // protect() gọi trong Connect() vẫn hiệu lực sau khi TUN lên.
        broadcast(ACTION_TUNNEL_CHECKING)
        updateNotification("Đang kiểm tra máy chủ…")
        val tm = TunnelManager(this, this)
        tunnelManager = tm
        // Level 2: Kotlin owns network monitoring (tailscale-android pattern).
        // Go's netmon is disabled (nil); network changes come via this callback.
        NetworkMonitor.start(this) { available ->
            Log.i(TAG, "network changed: available=$available")
            tm.onNetworkChanged(available)
            if (!available) {
                broadcast(ACTION_TUNNEL_RECONNECTING)
                updateNotification("Mất mạng, đang chờ…")
            }
        }
        if (!tm.hasServeAddr()) {
            Log.e(TAG, "no serve address")
            broadcast(ACTION_TUNNEL_DOWN, reason = "Chưa quét mã QR máy chủ")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return false
        }
        broadcast(ACTION_TUNNEL_CONNECTING)
        updateNotification("Đang nối tunnel…")
        try {
            tm.connect()
        } catch (e: Exception) {
            Log.e(TAG, "tunnel connect failed: ${e.message}")
            broadcast(ACTION_TUNNEL_DOWN, reason = "Không nối được tunnel: ${e.message}")
            updateNotification("Không kết nối được")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return false
        }
        val fd = buildTun() ?: run {
            Log.e(TAG, "establish() failed")
            broadcast(ACTION_TUNNEL_DOWN, reason = "Không dựng được TUN (thiếu quyền VPN?)")
            tm.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return false
        }
        tunFd = fd
        updateNotification("Đã kết nối")
        Log.i(TAG, "TUN established (mtu=$MTU)")
        onTunnelReady(fd)
        // Verify end-to-end QUA TUNNEL trước khi báo UI "đã kết nối".
        // (Bug 2026-10-17: UI cũ tự fetch IP direct → báo nối được trong khi VPN chưa chạy.)
        verifyAndBroadcast(tm)
        return true
    }

    /**
     * Fetch IP egress qua tunnel của service. Thành công = tunnel → proxy → internet
     * thật sự thông. Thất bại = dừng service, broadcast DOWN để UI báo đúng.
     * Chạy ở background thread để không block onStartCommand.
     */
    private fun verifyAndBroadcast(tm: TunnelManager) {
        broadcast(ACTION_TUNNEL_VERIFYING)
        updateNotification("Đang xác minh kết nối…")
        Thread {
            var ip: String? = null
            for (attempt in 0 until 3) {
                if (attempt > 0) Thread.sleep(5000)
                ip = tm.fetchEgressIp()
                if (ip != null) break
            }
            if (ip != null) {
                Log.i(TAG, "egress verified via tunnel: $ip")
                updateNotification("Đã kết nối")
                broadcast(ACTION_TUNNEL_UP, ip = ip)
            } else {
                Log.e(TAG, "tunnel up but egress unreachable — stopping")
                broadcast(ACTION_TUNNEL_DOWN, reason = "Tunnel lên nhưng không tới được Internet")
                mainHandler.post { stopTunnel(); stopSelf() }
            }
        }.start()
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
        NetworkMonitor.stop(this)
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
