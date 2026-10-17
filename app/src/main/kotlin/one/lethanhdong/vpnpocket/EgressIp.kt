package one.lethanhdong.vpnpocket

import android.util.Log
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * P2.6: lấy IP egress để hiện trên UI.
 *
 * Traffic của chính app cũng đi qua TUN → forwarder → tunnel → proxy,
 * nên IP mà api.ipify.org thấy chính là IP egress của proxy.
 * Đã verify: TLS qua tunnel KHÔNG bị MITM (cert thật của server),
 * nên dùng HTTPS bình thường được.
 *
 * Chạy ở background thread; callback chạy trên thread gọi [fetch]
 * (caller tự post về main nếu cần update UI).
 */
object EgressIp {
    private const val TAG = "PocketVpn"
    private val IP_RE = Regex("[0-9a-fA-F.:]{3,45}")

    fun fetch(callback: (String?) -> Unit) {
        Thread {
            var ip: String? = null
            // Tunnel có thể chưa lên ngay → thử lại vài lần.
            for (attempt in 0 until 3) {
                if (attempt > 0) Thread.sleep(5000)
                ip = tryFetch()
                if (ip != null) break
            }
            callback(ip)
        }.start()
    }

    private fun tryFetch(): String? {
        return try {
            val conn = URL("https://api.ipify.org").openConnection() as HttpsURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()
            if (body.matches(IP_RE)) body else null
        } catch (e: Exception) {
            Log.w(TAG, "egress ip fetch: ${e.message}")
            null
        }
    }
}
