package one.lethanhdong.vpnpocket

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast

/**
 * Màn hình Cài đặt (học từ mổ ProtonVPN: settings tổ chức theo nhóm).
 *
 * Nhóm áp dụng cho app cá nhân 1 server (không bịa):
 * - Máy chủ: quét lại QR (địa chỉ thật không hiện — là secret)
 * - Kết nối: chế độ TCP-only (Mode A), DNS qua DoH — đúng spec
 * - Ứng dụng: phiên bản
 */
class SettingsActivity : Activity() {

    private val bg = 0xFF0F1419.toInt()
    private val cardBg = 0xFF1A2230.toInt()
    private val textMain = 0xFFFFFFFF.toInt()
    private val textSub = 0xFF8B95A5.toInt()
    private val blue = 0xFF2E7CF6.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textSub)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24); bottomMargin = dp(8); leftMargin = dp(4) }
    }

    private fun row(labelStr: String, valueStr: String, onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cardBg, 14)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(TextView(this@SettingsActivity).apply {
                text = labelStr
                textSize = 16f
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = valueStr
                textSize = 14f
                setTextColor(textSub)
            })
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    private fun buildUi(): LinearLayout {
        val tm = TunnelManager(this, null)
        val hasServer = tm.hasServeAddr()

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

        // Nhóm: Máy chủ
        root.addView(sectionTitle("MÁY CHỦ"))
        root.addView(row(
            "Địa chỉ máy chủ",
            if (hasServer) "Đã cấu hình" else "Chưa có",
            onClick = {
                startActivity(Intent(this, QrScanActivity::class.java))
            }
        ))

        // Nhóm: Kết nối (đúng spec Mode A)
        root.addView(sectionTitle("KẾT NỐI"))
        root.addView(row("Chế độ", "TCP-only (Mode A)"))
        root.addView(row("DNS", "DoH qua tunnel"))
        root.addView(row("Gọi thoại/game", "Không hỗ trợ ở Mode A"))

        // Nhóm: Ứng dụng
        root.addView(sectionTitle("ỨNG DỤNG"))
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        } catch (_: Exception) { "—" }
        root.addView(row("Phiên bản", "VPN Pocket $version"))
        root.addView(Button(this).apply {
            text = "Xóa địa chỉ máy chủ"
            textSize = 15f
            setTextColor(0xFFE5484D.toInt())
            background = rounded(cardBg, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(16) }
            stateListAnimator = null
            isAllCaps = false
            setOnClickListener {
                // Xóa serve addr khỏi EncryptedSharedPreferences.
                // TunnelManager chưa có hàm xóa → dùng saveServeAddr("").
                tm.saveServeAddr("")
                Toast.makeText(this@SettingsActivity, "Đã xóa địa chỉ máy chủ", Toast.LENGTH_SHORT).show()
                recreate()
            }
        })

        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(24))
        })
        return root
    }
}
