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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

/**
 * Màn hình chính VPN Pocket (thiết kế lại theo 8b, học từ mổ ProtonVPN 5.20.74.0).
 *
 * Những gì Proton hiện (verify từ strings.xml decompile):
 * - connection_details_server/country/city: tên + vị trí server
 * - connection_details_my_ip/my_ipv4/my_ipv6: IP của user
 * - connection_details_protocol: protocol đang dùng
 * - connection_details_connected_for: thời gian đã kết nối
 * - connection_details_section_vpn_speed: tốc độ
 *
 * Áp dụng trung thực cho app cá nhân 1 server (không bịa):
 * - Server: "Máy chủ cá nhân" (không bịa quốc gia — IP egress xoay, không tra geo)
 * - IP: IP egress thật (lấy qua tunnel khi đã nối)
 * - Protocol: "TCP" (Mode A là TCP-only — đúng spec)
 * - Thời gian kết nối: đếm thật từ lúc nối thành công
 */
class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var ipText: TextView
    private lateinit var durationText: TextView
    private lateinit var connectBtn: Button
    private var connected = false
    private var connectedSince: Long = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (connected) {
                durationText.text = "Đã kết nối: ${formatDuration(System.currentTimeMillis() - connectedSince)}"
                uiHandler.postDelayed(this, 1000)
            }
        }
    }

    // Bảng màu.
    private val bg = 0xFF0F1419.toInt()
    private val cardBg = 0xFF1A2230.toInt()
    private val rowBg = 0xFF222C3D.toInt()
    private val textMain = 0xFFFFFFFF.toInt()
    private val textSub = 0xFF8B95A5.toInt()
    private val blue = 0xFF2E7CF6.toInt()
    private val green = 0xFF34C759.toInt()
    private val gray = 0xFF3A4356.toInt()
    private val red = 0xFFE5484D.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật trạng thái QR (không reset trạng thái kết nối).
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(textSub)
    }

    private fun value(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
    }

    private fun infoRow(labelStr: String, valueView: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(rowBg, 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(label(labelStr))
            addView(valueView)
        }

    private fun buildUi(): LinearLayout {
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            background = rounded(0xFF6B7280.toInt(), 6)
        }
        statusText = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMain)
            text = "Chưa kết nối"
        }
        serverText = value("Máy chủ cá nhân")
        ipText = value("—")
        durationText = TextView(this).apply {
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
            stateListAnimator = null
            isAllCaps = false
            setOnClickListener { onConnectClicked() }
        }

        val settingsBtn = ImageButton(this).apply {
            // Dùng icon hệ thống (không cần thêm drawable).
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(textSub)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
            addView(TextView(this@MainActivity).apply {
                text = "VPN Pocket"
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(settingsBtn)
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot)
            addView(statusText.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) }
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 20)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(statusRow)
            addView(Space(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(6))
            })
            addView(durationText)
            addView(infoRow("Máy chủ", serverText))
            addView(infoRow("Địa chỉ IP", ipText))
            addView(infoRow("Giao thức", value("TCP • DNS qua DoH")))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(48), dp(24), dp(24))
            addView(header)
            addView(card)
            addView(Space(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(32))
            })
            addView(connectBtn)
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
        else "%02d:%02d".format(m, sec)
    }

    private fun setUiState(state: UiState, ip: String? = null) {
        when (state) {
            UiState.IDLE -> {
                statusText.text = "Chưa kết nối"
                statusDot.background = rounded(0xFF6B7280.toInt(), 6)
                ipText.text = "—"
                durationText.text = ""
                uiHandler.removeCallbacks(tick)
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
                ipText.text = ip ?: "—"
                connectedSince = System.currentTimeMillis()
                uiHandler.post(tick)
                connectBtn.text = "NGẮT KẾT NỐI"
                connectBtn.background = rounded(red, 16)
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
        EgressIp.fetch { ip ->
            runOnUiThread {
                if (!connected) return@runOnUiThread
                if (ip != null) {
                    setUiState(UiState.CONNECTED, ip)
                } else {
                    connected = false
                    setUiState(UiState.IDLE)
                    statusText.text = "Không kết nối được — thử lại"
                    startService(Intent(this, PocketVpnService::class.java).apply {
                        action = PocketVpnService.ACTION_DISCONNECT
                    })
                }
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
