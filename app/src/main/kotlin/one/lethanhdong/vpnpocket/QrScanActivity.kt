package one.lethanhdong.vpnpocket

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * P2.6: quét QR serve address (CameraX + ZXing).
 * QR chứa chuỗi tailcat address (từ qr_serve.py trên VM).
 * Quét xong → lưu EncryptedSharedPreferences → đóng.
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PocketVpn"
        private const val REQ_CAMERA = 2001
    }

    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader()
    private var scanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(FrameLayout(this).apply { addView(previewView) })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "Cần quyền camera để quét mã", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyze) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "camera bind: ${e.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        if (scanned) { image.close(); return }
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val source = PlanarYUVLuminanceSource(
                bytes, image.width, image.height,
                0, 0, image.width, image.height, false
            )
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)))
            val text = result.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                scanned = true
                onQrScanned(text)
            }
        } catch (_: Exception) {
            // Chưa đọc được — frame tiếp theo.
        } finally {
            image.close()
        }
    }

    private fun onQrScanned(text: String) {
        runOnUiThread {
            // Validate: phải là tailcat serve address (khớp regex của qr_serve.py).
            if (!text.matches(Regex("tcp[A-Za-z0-9_.\\-]+"))) {
                Toast.makeText(this, "Mã không đúng (cần mã QR serve address)", Toast.LENGTH_LONG).show()
                scanned = false
                return@runOnUiThread
            }
            TunnelManager(this, null).saveServeAddr(text)
            Toast.makeText(this, "Đã lưu địa chỉ máy chủ", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
