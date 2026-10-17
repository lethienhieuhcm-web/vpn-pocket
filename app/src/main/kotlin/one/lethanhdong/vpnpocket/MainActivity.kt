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

// P2.6: UI 1 nút + quét QR serve address.
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var connectBtn: Button
    private lateinit var qrBtn: Button
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply { textSize = 16f }
        connectBtn = Button(this).apply {
            text = "Kết nối"
            setOnClickListener { onConnectClicked() }
        }
        qrBtn = Button(this).apply {
            text = "Quét mã QR máy chủ"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QrScanActivity::class.java))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(statusView)
            addView(connectBtn)
            addView(qrBtn)
        })
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val tm = TunnelManager(this, null)
        statusView.text = if (tm.hasServeAddr()) {
            "VPN Pocket — đã có địa chỉ máy chủ"
        } else {
            "VPN Pocket — chưa quét mã QR máy chủ"
        }
        qrBtn.text = if (tm.hasServeAddr()) "Quét lại mã QR" else "Quét mã QR máy chủ"
    }

    private fun onConnectClicked() {
        if (connected) {
            startService(Intent(this, PocketVpnService::class.java).apply {
                action = PocketVpnService.ACTION_DISCONNECT
            })
            connected = false
            connectBtn.text = "Kết nối"
            refreshStatus()
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
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
        val tm = TunnelManager(this, null)
        if (!tm.hasServeAddr()) {
            statusView.text = "Quét mã QR máy chủ trước đã"
            startActivity(Intent(this, QrScanActivity::class.java))
            return
        }
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
