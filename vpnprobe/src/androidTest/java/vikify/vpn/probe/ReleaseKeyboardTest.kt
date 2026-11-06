package vikify.vpn.probe

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Exercises the installed release without accessing its private storage. */
class ReleaseKeyboardTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ui = ins.uiAutomation
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun root() = ui.rootInActiveWindow ?: ui.windows.sortedByDescending { it.layer }
        .mapNotNull { it.root }.firstOrNull { it.packageName == "vikify.vpn" }
    private fun nodes(n: AccessibilityNodeInfo? = root()): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Release keyboard UI did not reach expected state")
    }
    private fun tapNode(find: () -> AccessibilityNodeInfo?) {
        // A new Compose dialog can expose its window before publishing nodes.
        // Do not scroll it away while waiting for the entry animation.
        SystemClock.sleep(600)
        fun visible(): Boolean {
            val node = find() ?: return false
            return node.isVisibleToUser && !Rect().also(node::getBoundsInScreen).isEmpty
        }
        for (attempt in 0 until 12) {
            if (visible()) break
            nodes().firstOrNull { it.isScrollable && it.className == "android.widget.ScrollView" }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(500)
        }
        await { visible() }
        SystemClock.sleep(700)
        var rect = Rect().also(requireNotNull(find())::getBoundsInScreen)
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(350)
            val next = Rect().also(requireNotNull(find())::getBoundsInScreen)
            if (rect == next) break
            rect = next
        }
        check(!rect.isEmpty)
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        SystemClock.sleep(400)
    }
    private fun tap(text: String) {
        ins.sendStatus(2, Bundle().apply { putString("auditStep", "Keyboard audit: $text") })
        tapNode { nodes().firstOrNull { it.text?.toString() == text } }
    }
    private fun capture(name: String) {
        ui.takeScreenshot()?.let { bitmap ->
            File(ins.targetContext.filesDir, "keyboard-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    @Test fun keyboardAndLargeLandscapeOnRelease() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseKeyboard") == "yes")
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val originalFont = shell("settings get system font_scale").trim()
        val originalAutoRotation = shell("settings get system accelerometer_rotation").trim()
        val originalRotation = shell("settings get system user_rotation").trim()
        try {
            for (landscape in listOf(false, true)) {
                shell("settings put system font_scale ${if (landscape) "2.0" else "1.0"}")
                shell("am force-stop vikify.vpn")
                shell("am start -W -f 0x10008000 -n vikify.vpn/vikify.vpn.ui.MainActivity")
                assertTrue(ui.setRotation(if (landscape) android.app.UiAutomation.ROTATION_FREEZE_90
                    else android.app.UiAutomation.ROTATION_FREEZE_0))
                tap("Details"); tap("Replace link")
                tapNode { nodes().firstOrNull { it.className == "android.widget.EditText" } }
                await { nodes().any { it.className == "android.widget.EditText" && it.isFocused } }
                val editor = nodes().first { it.className == "android.widget.EditText" }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "http://invalid.example/sub/abcdefghijklmnop")
                }))
                await { shell("dumpsys input_method").contains("mInputShown=true") }
                SystemClock.sleep(2000)
                val focused = nodes().firstOrNull { it.className == "android.widget.EditText" && it.isFocused && it.isVisibleToUser }
                assertNotNull("Input remains visible with keyboard", focused)
                capture(if (landscape) "landscape-200" else "portrait")
                assertTrue(requireNotNull(focused).performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
                await { nodes().any { it.text?.toString()?.startsWith("Use an HTTPS address") == true } }
                await { shell("dumpsys input_method").contains("mInputShown=false") }
                tap("Cancel"); shell("input keyevent 4")
                await { nodes().any { it.text?.toString() == "Alternative · XHTTP" } }
                assertFalse(shell("dumpsys activity services vikify.vpn").contains("VikifyVpnService"))
                ins.sendStatus(2, Bundle().apply { putString("auditStep",
                    "Release keyboard ${if (landscape) "landscape 200%" else "portrait"}: PASS; XHTTP kept; VPN off") })
            }
        } finally {
            capture("final")
            if (originalFont == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $originalFont")
            ui.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            for ((key, value) in listOf("accelerometer_rotation" to originalAutoRotation,
                "user_rotation" to originalRotation)) {
                if (value == "null") shell("settings delete system $key")
                else shell("settings put system $key $value")
            }
        }
    }
}
