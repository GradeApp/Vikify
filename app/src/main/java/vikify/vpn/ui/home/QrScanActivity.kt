package vikify.vpn.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import vikify.vpn.R
import vikify.vpn.ui.theme.VikifyTheme

class QrScanActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val delivered = AtomicBoolean()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            unavailable()
            return
        }
        val view = PreviewView(this)
        setContent {
            VikifyTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .75f))
                        .safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.qr_aim), color = Color.White)
                        Button(onClick = { finish() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                val camera = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { frame ->
                    try {
                        if (!delivered.get()) SubscriptionQr.cameraFrame(frame)?.let { value ->
                            if (delivered.compareAndSet(false, true)) runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, value))
                                    finish()
                                }
                            }
                        }
                    } finally { frame.close() }
                }
                provider.bindToLifecycle(this, camera, preview, analysis)
            } catch (_: Exception) { unavailable() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unavailable() {
        setResult(RESULT_UNAVAILABLE)
        finish()
    }

    override fun onDestroy() {
        delivered.set(true)
        super.onDestroy()
        worker.shutdown()
    }

    companion object {
        const val EXTRA_TEXT = "decoded_qr"
        const val RESULT_UNAVAILABLE = RESULT_FIRST_USER
    }
}
