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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast

/**
 * Màn hình chính VPN Pocket — bottom navigation 3 tab (Trang chủ / Hồ sơ / Cài đặt).
 *
 * Học từ mổ ProtonVPN 5.20.74.0 (screenshot thật từ máy Henrry 2026-10-17):
 * - Bottom nav: Home / Countries / Profiles / Settings → gọn lại 3 tab
 *   (bỏ Countries: app cá nhân 1 server, không bịa danh sách nước)
 * - Tab Hồ sơ: 2 profile Mode A (P2) / Mode B (P3 "Sắp có") — như Proton cho tạo profile
 * - Section header màu accent: "Features" / "Connection" / "General" → "MÁY CHỦ" / "KẾT NỐI" / "ỨNG DỤNG"
 * - Card bo tròn dark, icon + label + value
 * - Accent tím Proton (#7C5CFF)
 * - Connection details (verify từ strings.xml): server, My IP, protocol, connected_for
 */
class MainActivity : Activity() {

    // --- Views tab Home ---
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var ipText: TextView
    private lateinit var durationText: TextView
    private lateinit var connectBtn: Button

    // --- Tab container ---
    private lateinit var homeView: LinearLayout
    private lateinit var serversView: LinearLayout
    private lateinit var profilesView: LinearLayout
    private lateinit var settingsView: LinearLayout
    private lateinit var tabHome: LinearLayout
    private lateinit var tabServers: LinearLayout
    private lateinit var tabProfiles: LinearLayout
    private lateinit var tabSettings: LinearLayout

    // 0=Home, 1=Profiles, 2=Settings
    private var currentTab = 0

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

    // Bảng màu (học Proton: dark + accent tím).
    private val bg = 0xFF14141F.toInt()
    private val cardBg = 0xFF23232F.toInt()
    private val rowBg = 0xFF2A2A3A.toInt()
    private val navBg = 0xFF1A1A26.toInt()
    private val textMain = 0xFFFFFFFF.toInt()
    private val textSub = 0xFF8B95A5.toInt()
    private val accent = 0xFF7C5CFF.toInt() // tím Proton
    private val green = 0xFF34C759.toInt()
    private val gray = 0xFF3A4356.toInt()
    private val red = 0xFFE5484D.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.init(this)
        CrashUploader.uploadPending(this)
        homeView = buildHomeView()
        serversView = buildServersView()
        serversView.visibility = View.GONE
        profilesView = buildProfilesView()
        profilesView.visibility = View.GONE
        settingsView = buildSettingsView()
        settingsView.visibility = View.GONE
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                addView(homeView)
                addView(serversView)
                addView(profilesView)
                addView(settingsView)
            })
            addView(buildBottomNav())
        })
        requestNotificationPermission()
        // Nghe trạng thái thật từ service (broadcast từng giai đoạn, học ProtonVPN).
        val filter = android.content.IntentFilter().apply {
            addAction(PocketVpnService.ACTION_TUNNEL_CHECKING)
            addAction(PocketVpnService.ACTION_TUNNEL_CONNECTING)
            addAction(PocketVpnService.ACTION_TUNNEL_VERIFYING)
            addAction(PocketVpnService.ACTION_TUNNEL_RECONNECTING)
            addAction(PocketVpnService.ACTION_TUNNEL_UP)
            addAction(PocketVpnService.ACTION_TUNNEL_DOWN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(tunnelReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(tunnelReceiver, filter)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(tick)
        uiHandler.removeCallbacks(connectTimeout)
        try { unregisterReceiver(tunnelReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    // ============ BOTTOM NAV ============

    private fun navTab(iconRes: Int, labelStr: String, selected: Boolean, onClick: () -> Unit): LinearLayout {
        // M3: pill indicator (56x32dp) sau icon active, icon outline->filled
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(32))
            background = rounded(if (selected) 0x33267BFF.toInt() else 0x00000000, 16)
            setPadding(0, dp(4), 0, dp(4))
        }
        val icon = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setImageResource(iconRes)
            // Tint theo trạng thái: active = tím sáng, inactive = xám
            setColorFilter(if (selected) 0xFFD0BCFF.toInt() else 0xFF888888.toInt())
        }
        pill.addView(icon)
        val label = TextView(this).apply {
            text = labelStr
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) 0xFFD0BCFF.toInt() else textSub)
            setPadding(0, dp(4), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            addView(pill)
            addView(label)
            setOnClickListener { onClick() }
            tag = pill // lưu pill để setTabSelected đổi được
        }
    }

    private fun buildBottomNav(): LinearLayout {
        tabHome = navTab(R.drawable.ic_home, "Trang chủ", true) { selectTab(0) }
        tabServers = navTab(R.drawable.ic_language, "Máy chủ", false) { selectTab(1) }
        tabProfiles = navTab(R.drawable.ic_layers, "Hồ sơ", false) { selectTab(2) }
        tabSettings = navTab(R.drawable.ic_settings, "Cài đặt", false) { selectTab(3) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(navBg)
            setPadding(0, dp(4), 0, dp(8))
            addView(tabHome)
            addView(tabServers)
            addView(tabProfiles)
            addView(tabSettings)
        }
    }

    private fun setTabSelected(tab: LinearLayout, selected: Boolean) {
        val pill = tab.tag as LinearLayout
        val icon = pill.getChildAt(0) as android.widget.ImageView
        val label = tab.getChildAt(1) as TextView
        pill.background = rounded(if (selected) 0x33267BFF.toInt() else 0x00000000, 16)
        icon.setColorFilter(if (selected) 0xFFD0BCFF.toInt() else 0xFF888888.toInt())
        label.setTextColor(if (selected) 0xFFD0BCFF.toInt() else textSub)
    }

    private fun selectTab(index: Int) {
        currentTab = index
        homeView.visibility = if (index == 0) View.VISIBLE else View.GONE
        serversView.visibility = if (index == 1) View.VISIBLE else View.GONE
        profilesView.visibility = if (index == 2) View.VISIBLE else View.GONE
        settingsView.visibility = if (index == 3) View.VISIBLE else View.GONE
        setTabSelected(tabHome, index == 0)
        setTabSelected(tabServers, index == 1)
        setTabSelected(tabProfiles, index == 2)
        setTabSelected(tabSettings, index == 3)
        if (index == 1) refreshServers()
        if (index == 2) refreshProfiles()
        if (index == 3) refreshSettings()
    }

    // ============ TAB HOME ============

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

    private fun buildHomeView(): LinearLayout {
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
            background = rounded(accent, 32) // M3 pill: bo tròn full
            // Icon power bên trái chữ
            val icon = resources.getDrawable(R.drawable.ic_power_settings_new, theme)
            icon.setBounds(0, 0, dp(24), dp(24))
            icon.setTint(Color.WHITE)
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            )
            stateListAnimator = null
            isAllCaps = false
            setOnClickListener { onConnectClicked() }
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
            addView(TextView(this@MainActivity).apply {
                text = "VPN Pocket"
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(24) }
            })
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
                connectBtn.background = rounded(accent, 16)
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
            connecting = false
            uiHandler.removeCallbacks(connectTimeout)
            setUiState(UiState.IDLE)
            return
        }
        if (connecting) return // đang nối rồi, tránh bấm 2 lần
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

    // Service là nguồn duy nhất của trạng thái "đã kết nối".
    // (Bug 2026-10-17: UI cũ tự fetch IP direct → báo nối được trong khi VPN chưa chạy.)
    // Service broadcast từng giai đoạn (học ProtonVPN VpnState); UI chỉ nghe và hiển thị.
    private val tunnelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                PocketVpnService.ACTION_TUNNEL_CHECKING -> {
                    if (connecting) statusText.text = "Đang kiểm tra máy chủ…"
                }
                PocketVpnService.ACTION_TUNNEL_CONNECTING -> {
                    if (connecting) statusText.text = "Đang nối tunnel…"
                }
                PocketVpnService.ACTION_TUNNEL_VERIFYING -> {
                    if (connecting) statusText.text = "Đang xác minh kết nối…"
                }
                PocketVpnService.ACTION_TUNNEL_RECONNECTING -> {
                    statusText.text = "Đang kết nối lại…"
                    statusDot.background = rounded(0xFFFFB340.toInt(), 6)
                }
                PocketVpnService.ACTION_TUNNEL_UP -> {
                    val ip = intent.getStringExtra(PocketVpnService.EXTRA_IP)
                    if (connecting) {
                        connecting = false
                        connected = true
                        uiHandler.removeCallbacks(connectTimeout)
                        setUiState(UiState.CONNECTED, ip)
                    }
                }
                PocketVpnService.ACTION_TUNNEL_DOWN -> {
                    val reason = intent.getStringExtra(PocketVpnService.EXTRA_REASON)
                    if (connecting || connected) {
                        connecting = false
                        connected = false
                        uiHandler.removeCallbacks(connectTimeout)
                        setUiState(UiState.IDLE)
                        statusText.text = reason ?: "Không kết nối được — thử lại"
                        if (reason != null) {
                            Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private var connecting = false
    private val connectTimeout = Runnable {
        if (connecting) {
            connecting = false
            connected = false
            setUiState(UiState.IDLE)
            statusText.text = "Không kết nối được — thử lại"
            startService(Intent(this, PocketVpnService::class.java).apply {
                action = PocketVpnService.ACTION_DISCONNECT
            })
        }
    }

    private fun startVpn() {
        val tm = TunnelManager(this, null)
        if (!tm.hasServeAddr()) {
            // Chưa có server → mở tab Cài đặt để quét QR.
            selectTab(3)
            Toast.makeText(this, "Quét mã QR máy chủ trước", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, PocketVpnService::class.java).apply {
            action = PocketVpnService.ACTION_CONNECT
        })
        connecting = true
        connected = false
        setUiState(UiState.CONNECTING)
        // Timeout 45s: service không broadcast = có vấn đề → báo lỗi, không treo ở "Đang kết nối".
        uiHandler.postDelayed(connectTimeout, 45_000)
    }

    // ============ TAB HỒ SƠ ============
    // Học Proton: Profiles tab cho tạo/chọn profile kết nối.
    // App mình: 2 profile cố định — Mode A (P2, khả dụng), Mode B (P3, "Sắp có").

    private lateinit var profileModeAStatus: TextView

    private fun profileCard(
        name: String,
        desc: String,
        statusView: TextView,
        enabled: Boolean,
        onClick: (() -> Unit)?
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(cardBg, 14)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        alpha = if (enabled) 1f else 0.5f
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = name
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textMain)
            })
            addView(TextView(this@MainActivity).apply {
                text = desc
                textSize = 14f
                setTextColor(textSub)
            })
        })
        addView(statusView)
        if (enabled && onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun profileStatus(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        background = rounded(0xFF2A2A3A.toInt(), 8)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun buildProfilesView(): LinearLayout {
        profileModeAStatus = profileStatus("", textSub)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Hồ sơ"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        })
        root.addView(profileCard(
            "Mode A",
            "TCP qua máy chủ cá nhân • DNS qua DoH",
            profileModeAStatus,
            enabled = true,
            onClick = {
                // Chọn Mode A = về tab Trang chủ để kết nối.
                selectTab(0)
                if (!connected) onConnectClicked()
            }
        ))
        root.addView(profileCard(
            "Mode B",
            "ProxyScrape Residential • UDP cho gọi thoại",
            profileStatus("Sắp có", textSub),
            enabled = false,
            onClick = null
        ))
        root.addView(TextView(this).apply {
            text = "Mode B (P3) cho phép gọi thoại/game qua UDP. Hiện tại chỉ Mode A khả dụng."
            textSize = 13f
            setTextColor(textSub)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        })
        return root
    }

    // ============ TAB MÁY CHỦ ============
    private lateinit var serverStatusText: TextView
    private lateinit var serverPingText: TextView

    private fun buildServersView(): LinearLayout {
        serverStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(textSub)
        }
        serverPingText = TextView(this).apply {
            textSize = 14f
            setTextColor(textSub)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Máy chủ"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        })
        root.addView(sectionTitle("KHẢ DỤNG"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cardBg, 14)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_dns)
                setColorFilter(accent)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "Máy chủ cá nhân"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(textMain)
                })
                addView(serverStatusText)
                addView(serverPingText)
            })
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
                background = rounded(0xFF4CAF50.toInt(), 6)
            })
        })
        root.addView(sectionTitle("SẮP CÓ"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cardBg, 14)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            alpha = 0.55f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_public)
                setColorFilter(textSub)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "Mode B — Residential"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(textMain)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "SOCKS5 + UDP • P3"
                    textSize = 14f
                    setTextColor(textSub)
                })
            })
        })
        return root
    }

    private fun refreshServers() {
        val serveAddr: String? = TunnelManager(this, null).getServeAddr()
        if (serveAddr != null) {
            serverStatusText.text = "Đã cấu hình • TCP"
            Thread {
                val ms = measurePing(serveAddr)
                runOnUiThread {
                    serverPingText.text = if (ms >= 0) "Ping: ${ms}ms" else "Ping: không đo được"
                }
            }.start()
        } else {
            serverStatusText.text = "Chưa cấu hình — quét QR để thêm"
            serverPingText.text = ""
        }
    }

    private fun measurePing(serveAddr: String): Long {
        return try {
            val parts = serveAddr.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 443
            val start = System.currentTimeMillis()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 5000)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1
        }
    }

    private fun refreshProfiles() {
        if (connected) {
            profileModeAStatus.text = "Đang dùng"
            profileModeAStatus.setTextColor(green)
        } else {
            profileModeAStatus.text = "Khả dụng"
            profileModeAStatus.setTextColor(accent)
        }
    }

    // ============ TAB CÀI ĐẶT ============

    private lateinit var settingsServerValue: TextView

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent) // header màu accent như Proton
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24); bottomMargin = dp(8); leftMargin = dp(4) }
    }

    private fun settingRow(labelStr: String, valueView: TextView, onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cardBg, 14)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(TextView(this@MainActivity).apply {
                text = labelStr
                textSize = 16f
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueView)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    private fun settingValue(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(textSub)
    }

    private fun buildSettingsView(): LinearLayout {
        settingsServerValue = settingValue("—")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Cài đặt"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
        root.addView(sectionTitle("MÁY CHỦ"))
        root.addView(settingRow("Địa chỉ máy chủ", settingsServerValue, onClick = {
            startActivity(Intent(this, QrScanActivity::class.java))
        }))
        root.addView(sectionTitle("KẾT NỐI"))
        root.addView(settingRow("Chế độ", settingValue("TCP-only (Mode A)")))
        root.addView(settingRow("DNS", settingValue("DoH qua tunnel")))
        root.addView(settingRow("Gọi thoại/game", settingValue("Không hỗ trợ ở Mode A")))
        root.addView(sectionTitle("ỨNG DỤNG"))
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        } catch (_: Exception) { "—" }
        root.addView(settingRow("Phiên bản", settingValue("VPN Pocket $version")))
        // Xem log crash gần nhất (debug).
        root.addView(settingRow("Nhật ký lỗi", settingValue(
            CrashHandler.latestCrash(this)?.name ?: "Chưa có"
        ), onClick = {
            val log = CrashHandler.readCrash(this)
            if (log != null) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Nhật ký lỗi")
                    .setMessage(log.take(4000))
                    .setPositiveButton("Đóng", null)
                    .setNeutralButton("Copy") { _, _ ->
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                        Toast.makeText(this, "Đã copy", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                Toast.makeText(this, "Chưa có log lỗi", Toast.LENGTH_SHORT).show()
            }
        }))
        // Cấu hình tự gửi log crash qua Telegram.
        root.addView(settingRow("Tự gửi log lỗi", settingValue(
            if (CrashUploader.isConfigured(this)) "Đã bật" else "Chưa cấu hình"
        ), onClick = {
            val tokenInput = android.widget.EditText(this).apply { hint = "Bot token" }
            val chatInput = android.widget.EditText(this).apply { hint = "Chat ID" }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(tokenInput)
                addView(chatInput)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Cấu hình gửi log")
                .setView(layout)
                .setPositiveButton("Lưu") { _, _ ->
                    val t = tokenInput.text.toString().trim()
                    val c = chatInput.text.toString().trim()
                    if (t.isNotEmpty() && c.isNotEmpty()) {
                        CrashUploader.saveConfig(this, t, c)
                        Toast.makeText(this, "Đã bật tự gửi log", Toast.LENGTH_SHORT).show()
                        refreshSettings()
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }))
        root.addView(Button(this).apply {
            text = "Xóa địa chỉ máy chủ"
            textSize = 15f
            setTextColor(red)
            background = rounded(cardBg, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(16) }
            stateListAnimator = null
            isAllCaps = false
            setOnClickListener {
                TunnelManager(this@MainActivity, null).saveServeAddr("")
                Toast.makeText(this@MainActivity, "Đã xóa địa chỉ máy chủ", Toast.LENGTH_SHORT).show()
                refreshSettings()
            }
        })
        return root
    }

    private fun refreshSettings() {
        val has = TunnelManager(this, null).hasServeAddr()
        settingsServerValue.text = if (has) "Đã cấu hình ›" else "Chưa có ›"
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
