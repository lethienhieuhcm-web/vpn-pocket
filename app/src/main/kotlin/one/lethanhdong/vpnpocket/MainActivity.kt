package one.lethanhdong.vpnpocket

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// P1 skeleton — P2 mới có tunnel thật.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "VPN Pocket 0.1.0 — skeleton (P1)" }
        val btn = Button(this).apply {
            text = "Connect (P2)"
            setOnClickListener {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent != null) startActivityForResult(intent, 1)
                // TODO P2: start PocketVpnService sau khi user consent
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(btn)
        })
    }
}
