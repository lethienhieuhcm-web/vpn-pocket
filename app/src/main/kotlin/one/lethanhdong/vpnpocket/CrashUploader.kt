package one.lethanhdong.vpnpocket

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Tự động gửi log crash qua Cloudflare Worker về cho dev.
 * Không cần cấu hình token — URL Worker là public, POST không cần auth.
 * Agent đọc log qua GET /logs với admin key.
 * Henrry không cần làm gì thêm.
 */
object CrashUploader {
    private const val TAG = "CrashUploader"
    private const val WORKER_URL = "https://vpn-pocket-crash-reporter.trinhdaiphongbr.workers.dev/report"
    private const val PREF_SENT_PREFIX = "crash_sent_"

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        return ctx.getSharedPreferences("vpn_pocket_crash", Context.MODE_PRIVATE)
    }

    /**
     * Gửi các crash log chưa gửi. Chạy ở background thread.
     * Gọi từ MainActivity.onCreate. Không cần cấu hình trước.
     */
    fun uploadPending(ctx: Context) {
        Thread {
            try {
                val p = prefs(ctx)
                val dir = ctx.filesDir
                val files = dir.listFiles { f ->
                    f.name.startsWith("crash-") && f.name.endsWith(".txt") &&
                    !p.getBoolean(PREF_SENT_PREFIX + f.name, false)
                } ?: return@Thread
                for (f in files) {
                    if (sendViaWorker(f)) {
                        p.edit().putBoolean(PREF_SENT_PREFIX + f.name, true).apply()
                        Log.i(TAG, "uploaded ${f.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "upload failed", e)
            }
        }.start()
    }

    private fun sendViaWorker(file: File): Boolean {
        return try {
            val body = JSONObject().apply {
                put("filename", file.name)
                put("log", file.readText().take(60000))
            }.toString()

            val url = URL(WORKER_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "VPN-Pocket-CrashReporter")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            if (!ok) {
                Log.e(TAG, "worker upload failed: ${conn.responseCode}")
            }
            conn.disconnect()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "worker send failed", e)
            false
        }
    }
}
