package vikify.vpn.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Only the explicit user share action writes a report. No credentials, logs or free text. */
object DiagnosticSharing {
    private const val MAX_AGE_MS = 60 * 60 * 1000L

    fun cleanup(context: Context) {
        val directory = File(context.cacheDir, "diagnostic-reports")
        val now = System.currentTimeMillis()
        directory.listFiles()?.filter { it.isFile }?.forEach {
            if (now - it.lastModified() >= MAX_AGE_MS || it.lastModified() > now) it.delete()
        }
    }

    fun share(context: Context, report: DiagnosticReportData, json: Boolean, chooserTitle: String) {
        cleanup(context)
        val directory = File(context.cacheDir, "diagnostic-reports")
        check(directory.isDirectory || directory.mkdirs())
        // Bound cache growth even if many shares occur within the retention window.
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(7)?.forEach { it.delete() }
        val text = if (json) DiagnosticReport.toJson(report) else DiagnosticReport.toText(report)
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= 64 * 1024)
        val file = File(directory, "vikify-report-${UUID.randomUUID()}.${if (json) "json" else "txt"}")
        file.outputStream().use { it.write(bytes) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".diagnostic-reports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (json) "application/json" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Vikify diagnostic report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}
