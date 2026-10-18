package one.lethanhdong.vpnpocket

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tự động gửi log crash qua Telegram bot về cho dev.
 * Credentials lưu ở EncryptedSharedPreferences (nhập 1 lần ở Cài đặt).
 * Không hardcode token trong source (repo public).
 */
object CrashUploader {
    private const val TAG = "CrashUploader"
    private const val PREF_BOT_TOKEN = "crash_bot_token"
    private const val PREF_CHAT_ID = "crash_chat_id"
    private const val PREF_SENT_PREFIX = "crash_sent_"

    fun isConfigured(ctx: Context): Boolean {
        val p = prefs(ctx)
        return !p.getString(PREF_BOT_TOKEN, null).isNullOrBlank() &&
               !p.getString(PREF_CHAT_ID, null).isNullOrBlank()
    }

    fun saveConfig(ctx: Context, botToken: String, chatId: String) {
        prefs(ctx).edit()
            .putString(PREF_BOT_TOKEN, botToken.trim())
            .putString(PREF_CHAT_ID, chatId.trim())
            .apply()
    }

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx).build()
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx,
            "vpn_pocket_secure",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Gửi các crash log chưa gửi. Chạy ở background thread.
     * Gọi từ MainActivity.onCreate.
     */
    fun uploadPending(ctx: Context) {
        if (!isConfigured(ctx)) return
        Thread {
            try {
                val p = prefs(ctx)
                val token = p.getString(PREF_BOT_TOKEN, null) ?: return@Thread
                val chatId = p.getString(PREF_CHAT_ID, null) ?: return@Thread
                val dir = ctx.filesDir
                val files = dir.listFiles { f ->
                    f.name.startsWith("crash-") && f.name.endsWith(".txt") &&
                    !p.getBoolean(PREF_SENT_PREFIX + f.name, false)
                } ?: return@Thread
                for (f in files) {
                    if (sendViaTelegram(token, chatId, f)) {
                        p.edit().putBoolean(PREF_SENT_PREFIX + f.name, true).apply()
                        Log.i(TAG, "uploaded ${f.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "upload failed", e)
            }
        }.start()
    }

    private fun sendViaTelegram(token: String, chatId: String, file: File): Boolean {
        return try {
            val text = "🐛 VPN Pocket crash\n${file.name}\n\n${file.readText().take(3500)}"
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val body = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}" +
                       "&text=${URLEncoder.encode(text, "UTF-8")}"
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "telegram send failed", e)
            false
        }
    }
}
