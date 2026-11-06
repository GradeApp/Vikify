package vikify.vpn.probe

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in UI audit on a disposable emulator with a synthetic camera image. */
class QrImportTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ui = ins.uiAutomation

    @Test fun cameraAndPngImport() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qrAudit") == "emulator")
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            click("Add subscription"); click("QR code"); click("Scan with camera")
            permission("permission_deny_button")
            await { "Camera access was denied. Choose an image or paste the link." in texts() }
            click("Scan with camera")
            permission("permission_allow_foreground_only_button", "permission_allow_button")
            await(40_000) { "VLESS profiles from text" in texts() }
            image("camera-preview"); click("Add")
            await { "Profiles · 1" in texts() && "Camera QR test" in texts() }
            mark("Camera permission denial/retry and real camera-frame QR decoding PASS")
            click("Details"); click("Replace link"); click("QR code"); click("Choose image")
            await { nodes().any { it.packageName?.toString()?.contains("documentsui") == true } }
            SystemClock.sleep(800)
            fun qrFile() = nodes().firstOrNull {
                it.text?.toString() == "vikify-qa-qr.png" ||
                    it.contentDescription?.toString()?.startsWith("vikify-qa-qr.png,") == true
            }
            if (qrFile() == null) {
                val menu = nodes().firstOrNull { it.contentDescription?.toString() == "Show roots" }
                checkNotNull(menu) { "Document picker navigation unavailable" }
                tap(menu); click("Downloads")
            }
            await { qrFile() != null }
            SystemClock.sleep(350)
            tap(requireNotNull(qrFile()))
            await { "VLESS profiles from text" in texts() }
            image("png-preview"); click("Replace")
            await { "Subscription details" in texts() }; shell("input keyevent 4")
            await { "Profiles · 1" in texts() && "Camera QR test" in texts() }
            mark("PNG document picker, local QR decoding and replacement PASS")
        } catch (failure: Throwable) { image("failure"); throw failure }
    }
    private fun permission(vararg suffix: String) {
        await { nodes().any { n -> suffix.any { n.viewIdResourceName?.endsWith(it) == true } } }
        // Permission-window nodes can be published before its entry animation ends.
        SystemClock.sleep(600)
        tap(nodes().first { n -> suffix.any { n.viewIdResourceName?.endsWith(it) == true } })
    }
    private fun windowRoot(): AccessibilityNodeInfo? = ui.rootInActiveWindow
        ?: ui.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root }
    private fun nodes(n: AccessibilityNodeInfo? = windowRoot()): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
    private fun texts() = nodes().mapNotNull { it.text?.toString() }.toSet()
    private fun click(label: String) {
        await { nodes().any { it.text?.toString() == label && it.isVisibleToUser } }
        SystemClock.sleep(350)
        tap(nodes().first { it.text?.toString() == label && it.isVisibleToUser })
    }
    private fun tap(n: AccessibilityNodeInfo) {
        val r = Rect().also(n::getBoundsInScreen); check(!r.isEmpty)
        shell("input tap ${r.centerX()} ${r.centerY()}"); SystemClock.sleep(250)
    }
    private fun shell(s: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(s)).use { it.readBytes() }
    private fun await(timeout: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < end)
        error("QR UI state did not appear")
    }
    private fun image(name: String) {
        if (name == "failure") {
            File(ins.targetContext.filesDir, "qr-failure-ui.txt").writeText(nodes().joinToString("\n") {
                "${it.packageName} ${it.viewIdResourceName} ${it.text} ${it.contentDescription} ${Rect().also(it::getBoundsInScreen)}"
            })
        }
        ui.takeScreenshot()?.let { bitmap ->
            File(ins.targetContext.filesDir, "qr-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun mark(value: String) = ins.sendStatus(2, Bundle().apply { putString("auditStep", value) })
}
