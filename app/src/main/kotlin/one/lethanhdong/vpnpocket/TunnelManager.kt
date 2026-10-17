package one.lethanhdong.vpnpocket

import android.content.Context
import android.net.VpnService
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import vpntunnel.Protector
import vpntunnel.Vpntunnel

/**
 * P2.2: Quản lý tailcat tunnel (in-process qua vpntunnel AAR).
 *
 * - Client key: tự sinh 1 lần (Go: GenerateKey), lưu EncryptedSharedPreferences.
 *   Server không dùng --allow nên key chỉ để định danh ổn định, không cần đăng ký.
 * - Serve address: từ QR scan (P2.6), lưu EncryptedSharedPreferences.
 * - Protector: VpnService.protect(fd) — bắt buộc cho DERP socket,
 *   nếu không packet loop lại vào TUN (Android docs).
 */
class TunnelManager(
    private val context: Context,
    private val vpnService: VpnService?
) {
    companion object {
        private const val TAG = "PocketVpn"
        private const val PREFS = "vpn_pocket"
        private const val KEY_CLIENT_KEY = "client_key"
        private const val KEY_SERVE_ADDR = "serve_addr"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // @Volatile: đọc/ghi từ service thread (connect/disconnect)
    // và watchdog thread (protectScan, P2.6).
    @Volatile
    private var tunnel: vpntunnel.Tunnel? = null

    private val protector = object : Protector {
        override fun protect(fd: Long): Boolean {
            val ok = vpnService?.protect(fd.toInt()) ?: false
            if (!ok) Log.w(TAG, "protect($fd) failed")
            return ok
        }
    }

    /** Client key: có thì dùng, chưa có thì sinh mới + lưu. */
    fun getOrCreateClientKey(): String {
        prefs.getString(KEY_CLIENT_KEY, null)?.let { return it }
        val key = Vpntunnel.generateKey()
        prefs.edit().putString(KEY_CLIENT_KEY, key).apply()
        Log.i(TAG, "generated new client key")
        return key
    }

    fun getServeAddr(): String? = prefs.getString(KEY_SERVE_ADDR, null)

    fun saveServeAddr(addr: String) {
        prefs.edit().putString(KEY_SERVE_ADDR, addr.trim()).apply()
    }

    fun hasServeAddr(): Boolean = !getServeAddr().isNullOrBlank()

    /**
     * Kết nối tunnel. Ném exception nếu serveAddr sai / không tới được server.
     * Bên trong Connect() đã protect DERP socket (quét /proc).
     */
    @Throws(Exception::class)
    fun connect() {
        val addr = getServeAddr()
            ?: throw IllegalStateException("chưa quét QR serve address")
        disconnect()
        val t = Vpntunnel.newTunnel(addr, getOrCreateClientKey(), protector)
        t.connect()
        tunnel = t
        Log.i(TAG, "tunnel connected")
    }

    /** Quét lại /proc để protect DERP socket mới (watchdog 30s gọi). */
    fun protectScan(): Int {
        return try {
            tunnel?.protectScan()?.toInt() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "protectScan: ${e.message}")
            0
        }
    }

    fun getTunnel(): vpntunnel.Tunnel? = tunnel

    fun disconnect() {
        try {
            tunnel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "tunnel close: ${e.message}")
        }
        tunnel = null
    }
}
