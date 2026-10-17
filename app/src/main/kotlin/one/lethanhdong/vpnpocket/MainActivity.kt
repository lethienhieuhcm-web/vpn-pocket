package one.lethanhdong.vpnpocket

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

/**
 * Màn hình chính: 1 nút kết nối + trạng thái + quét QR.
 * UI dựng bằng code (không XML), dark theme.
 */
class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var connectBtn: Button
    private lateinit var qrBtn: Button
    private var connected = false

    // Bảng màu.
    private val bg = 0xFF0F1419.toInt()
    private val cardBg = 0xFF1A2230.toInt()
    private val textMain = 0xFFFFFFFF.toInt()
    private val textSub = 0xFF8B95A5.toInt()
    private val blue = 0xFF2E7CF6.toInt()
    private val green = 0xFF34C759.toInt()
    private val gray = 0xFF3A4356.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshQrState()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun buildUi(): LinearLayout {
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            background = rounded(0xFF6B7280.toInt(), 6)
        }
        statusText = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMain)
            text = "Chưa kết nối"
        }
        ipText = TextView(this).apply {
            textSize = 14f
            setTextColor(textSub)
            text = ""
        }
        connectBtn = Button(this).apply {
            text = "KẾT NỐI"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(blue, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            )
            // Bỏ bóng + chữ hoa mặc định của Button.
            stateListAnimator = null
            isAllCaps = false
            setOnClickListener { onConnectClicked() }
        }
        qrBtn = Button(this).apply {
            text = "Quét mã QR máy chủ"
            textSize = 15f
            setTextColor(textSub)
            background = rounded(Color.TRANSPARENT, 12)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QrScanActivity::class.java))
            }
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot)
            val st = statusText.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) }
            }
            addView(st)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 20)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(statusRow)
            addView(Space(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(8))
            })
            addView(ipText)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(28), dp(56), dp(28), dp(28))
            addView(TextView(this@MainActivity).apply {
                text = "VPN Pocket"
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textMain)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Proxy cá nhân của bạn"
                textSize = 15f
                setTextColor(textSub)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(32) }
            })
            addView(card)
            addView(Space(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(40))
            })
            addView(connectBtn)
            addView(Space(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(12))
            })
            addView(qrBtn.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                )
            })
        }
    }

    private fun refreshQrState() {
        val tm = TunnelManager(this, null)
        qrBtn.text = if (tm.hasServeAddr()) "Quét lại mã QR máy chủ" else "Quét mã QR máy chủ"
    }

    private fun setUiState(state: UiState, ip: String? = null) {
        when (state) {
            UiState.IDLE -> {
                statusText.text = "Chưa kết nối"
                statusDot.background = rounded(0xFF6B7280.toInt(), 6)
                ipText.text = ""
                connectBtn.text = "KẾT NỐI"
                connectBtn.background = rounded(blue, 16)
            }
            UiState.CONNECTING -> {
                statusText.text = "Đang kết nối…"
                statusDot.background = rounded(0xFFFFB340.toInt(), 6)
                connectBtn.text = "ĐANG KẾT NỐI"
                connectBtn.background = rounded(gray, 16)
            }
            UiState.CONNECTED -> {
                statusText.text = "Đã kết nối"
                statusDot.background = rounded(green, 6)
                ipText.text = if (ip != null) "IP: $ip" else ""
                connectBtn.text = "NGẮT KẾT NỐI"
                connectBtn.background = rounded(0xFFE5484D.toInt(), 16)
            }
        }
    }

    private fun onConnectClicked() {
        if (connected) {
            startService(Intent(this, PocketVpnService::class.java).apply {
                action = PocketVpnService.ACTION_DISCONNECT
            })
            connected = false
            setUiState(UiState.IDLE)
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
            startActivity(Intent(this, QrScanActivity::class.java))
            return
        }
        startForegroundService(Intent(this, PocketVpnService::class.java).apply {
            action = PocketVpnService.ACTION_CONNECT
        })
        connected = true
        setUiState(UiState.CONNECTING)
        // Hiện IP egress khi tunnel lên.
        EgressIp.fetch { ip ->
            runOnUiThread {
                if (connected) setUiState(UiState.CONNECTED, ip)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private enum class UiState { IDLE, CONNECTING, CONNECTED }

    companion object {
        private const val REQ_VPN_CONSENT = 1001
    }
}
