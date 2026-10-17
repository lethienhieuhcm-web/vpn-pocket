package one.lethanhdong.vpnpocket

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// P2.1: xin quyền VPN → start PocketVpnService. Tunnel thật ở P2.2–P2.6.
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var connectBtn: Button
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply { text = "VPN Pocket — chưa kết nối" }
        connectBtn = Button(this).apply {
            text = "Kết nối"
            setOnClickListener { onConnectClicked() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(statusView)
            addView(connectBtn)
        })
        requestNotificationPermission()
    }

    private fun onConnectClicked() {
        if (connected) {
            startService(Intent(this, PocketVpnService::class.java).apply {
                action = PocketVpnService.ACTION_DISCONNECT
            })
            connected = false
            connectBtn.text = "Kết nối"
            statusView.text = "VPN Pocket — chưa kết nối"
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            // Chưa có consent: mở dialog hệ thống.
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, REQ_VPN_CONSENT)
        } else {
            startVpn()
        }
    }

    @Deprecated("dùng cho VpnService.prepare flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_CONSENT && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        startForegroundService(Intent(this, PocketVpnService::class.java).apply {
            action = PocketVpnService.ACTION_CONNECT
        })
        connected = true
        connectBtn.text = "Ngắt kết nối"
        statusView.text = "VPN Pocket — đang kết nối…"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        private const val REQ_VPN_CONSENT = 1001
    }
}
