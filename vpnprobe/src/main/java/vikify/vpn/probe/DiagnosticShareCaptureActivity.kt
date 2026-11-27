package vikify.vpn.probe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject

/**
 * Local-only report destination used by the opt-in release audit.
 * This activity belongs to the separate QA APK, never the distributed VPN APK.
 * It does not send data, print report contents, or retain incoming URI strings.
 */
class DiagnosticShareCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metadata = File(filesDir, "captured-diagnostic-meta.json")
        try {
            // Independent process avoids instrumentation lifecycle interception.
            // Keep launch evidence separate from successful payload capture.
            writeMetadata(File(filesDir, "captured-diagnostic-start.json"), JSONObject()
                .put("started", true).put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("mime", intent.type?.takeIf { it == "text/plain" || it == "application/json" }
                    ?: "UNSUPPORTED"))
            check(intent.action == Intent.ACTION_SEND)
            val mime = intent.type
            check(mime == "text/plain" || mime == "application/json")
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            val bytes = if (uri != null) {
                check(uri.scheme == "content")
                check(uri.authority == "vikify.vpn.diagnostic-reports")
                contentResolver.openInputStream(uri).use { stream ->
                    checkNotNull(stream)
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } else {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                checkNotNull(text)
                check(text.length <= MAX_BYTES)
                text.toString().toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_BYTES) }
            }
            check(bytes.isNotEmpty())
            val name = if (mime == "application/json") "captured-diagnostic.json" else "captured-diagnostic.txt"
            File(filesDir, name).writeBytes(bytes)
            writeMetadata(metadata, JSONObject().put("captured", true).put("mime", mime)
                .put("bytes", bytes.size).put("file", name).put("pid", Process.myPid()))
        } catch (_: Exception) {
            // Error details can themselves contain the URI or report payload.
            writeMetadata(metadata, JSONObject().put("captured", false)
                .put("code", "CAPTURE_REJECTED"))
        } finally {
            finish()
        }
    }

    private fun writeMetadata(file: File, result: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(result.toString())
        check(temporary.renameTo(file))
    }

    private companion object { const val MAX_BYTES = 65_536 }
}
