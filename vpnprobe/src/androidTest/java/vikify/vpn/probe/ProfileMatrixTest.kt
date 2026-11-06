package vikify.vpn.probe

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, release-app device audit. Uses only visible controls, never release private data. */
@RunWith(AndroidJUnit4::class)
class ProfileMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val context = instrumentation.targetContext
    private val names = listOf("Automatic", "Primary", "Backup server", "Backup 1", "Backup 2",
        "Alternative · XHTTP", "Last resort · WebSocket")

    @Test fun sevenProfilesCarryTraffic() {
        val phase = InstrumentationRegistry.getArguments().getString("matrix")
        assumeTrue(phase in listOf("sub3-initial", "sub4-switch", "sub3-return"))
        val tag = requireNotNull(phase)
        val results = JSONArray()
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        await { "Choose" in texts() }
        try {
            for ((i, name) in names.withIndex()) {
                val start = SystemClock.elapsedRealtime()
                val record = JSONObject().put("profile", name).put("phase", tag)
                instrumentation.sendStatus(2, Bundle().apply { putString("profileStart", name) })
                try {
                    select(name)
                    if (tag == "sub3-return") {
                        shell("am force-stop vikify.vpn")
                        shell("am start -W -n vikify.vpn/vikify.vpn.ui.MainActivity")
                        await { "Choose" in texts() && name in texts() }
                    }
                    click(if ("Switch profile" in texts()) "Switch profile" else "Connect")
                    await(65_000) {
                        val current = texts()
                        check("Connection failed" !in current) { "Profile connection failed" }
                        "VPN connected" in current && "Disconnect" in current && "Switch profile" !in current
                    }
                    val traffic = TunnelTrafficTest()
                    traffic.defaultNetworkCarriesDnsAndHttpsThroughVpn()
                    traffic.defaultHttpsStackDownloadsARealPage()
                    traffic.separateHttpsHostReturnsAnExitAddress()
                    assertTrue("VPN remains connected", "VPN connected" in texts())
                    screenshot("$tag-${i + 1}-connected")
                    record.put("passed", true).put("trafficChecks", 3)
                    if (tag != "sub4-switch") disconnect()
                } catch (failure: Throwable) {
                    record.put("passed", false).put("error", failure.javaClass.simpleName + ": " + failure.message)
                    screenshot("$tag-${i + 1}-failed")
                    disconnect()
                }
                record.put("seconds", (SystemClock.elapsedRealtime() - start) / 1000.0)
                results.put(record)
                File(context.filesDir, "matrix-$tag.json").writeText(results.toString(2))
                instrumentation.sendStatus(2, Bundle().apply { putString("profileResult", record.toString()) })
            }
        } finally {
            disconnect()
            select(names[5])
        }
        assertEquals(7, results.length())
        assertTrue("See per-profile records", (0 until results.length()).all { results.getJSONObject(it).getBoolean("passed") })
    }

    private fun select(name: String) {
        click("Choose")
        await { nodes(root()).count { it.isCheckable } == 7 }
        val rows = nodes(root()).filter { it.isCheckable }.toList()
        val target = rows.single { nodes(it).any { n -> n.text?.toString() == name } }
        if (target.isChecked) shell("input keyevent 4") else click(name)
        await { val t = texts(); "Choose" in t && name in t }
        // Reopen to verify the persisted radio selection, without clicking the profile twice.
        click("Choose")
        await { nodes(root()).any { it.isChecked && nodes(it).any { n -> n.text?.toString() == name } } }
        shell("input keyevent 4")
        await { "Choose" in texts() }
    }

    private fun disconnect() {
        if ("Disconnect" in texts()) {
            click("Disconnect")
            await { "Ready to connect" in texts() }
        } else if ("Cancel" in texts()) {
            click("Cancel")
            await { "Ready to connect" in texts() }
        }
    }

    private fun click(label: String) {
        await { nodes(root()).any { it.text?.toString() == label && it.isVisibleToUser } }
        val node = nodes(root()).single { it.text?.toString() == label && it.isVisibleToUser }
        val rect = Rect().also(node::getBoundsInScreen)
        check(!rect.isEmpty)
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        SystemClock.sleep(150)
    }
    private fun root(): AccessibilityNodeInfo? = automation.rootInActiveWindow
        ?.takeIf { it.packageName?.toString() == "vikify.vpn" }
    private fun texts() = nodes(root()).mapNotNull { it.text?.toString() }.toSet()
    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) {
            yield(node)
            for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i)))
        }
    }
    private fun await(timeout: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        error("UI condition timed out")
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).use { it.readBytes() }
    private fun screenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
