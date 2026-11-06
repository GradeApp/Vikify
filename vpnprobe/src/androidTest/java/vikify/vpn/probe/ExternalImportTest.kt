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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalImportTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ui = ins.uiAutomation
    private lateinit var fixtures: JSONObject

    @Test fun importsCompatibleFormatsWithoutLosingAccessOnRejectedReplacement() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("externalImport") == "yes")
        fixtures = JSONObject(File(ins.targetContext.filesDir, "external-fixtures.json").readText())
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        await { "Choose" in texts() }
        try {
            if (InstrumentationRegistry.getArguments().getString("consentAudit") == "yes") auditConsent()
            import("direct")
            assertTrue("Local profile count", "Profiles · 1" in texts())
            assertFalse("Local profiles do not have remote updates", "Update" in texts())
            assertTrue("External XHTTP" in texts())
            traffic("direct-xhttp")
            import("base64")
            assertTrue("Profiles · 3" in texts())
            for (name in listOf("External REALITY", "External XHTTP", "External WebSocket")) {
                choose(name); traffic(name.replace(' ', '-'))
            }
            choose("External XHTTP")
            startImport("mixed")
            await { "Import compatible" in texts() }
            assertTrue(texts().any { it.contains("Ready to import: 3. Skipped: 1.") })
            image("mixed-review")
            click("Cancel"); await { "Replace" in texts() }; click("Cancel")
            await { "Subscription details" in texts() }; back(); await { "Choose" in texts() }
            assertTrue("Cancelled review preserves original selection", "External XHTTP" in texts())
            mark("Mixed subscription review cancellation preserved saved profiles and selection")
            startImport("mixed"); await { "Import compatible" in texts() }; click("Import compatible")
            await { "Subscription details" in texts() }
            assertTrue("Skipped entries remain visible", "Skipped entries in the source: 1" in texts())
            back(); await { "Choose" in texts() }; choose("Mixed XHTTP"); traffic("mixed-xhttp")
            for ((key, prefix) in listOf("http" to "Use an HTTPS address", "insecure" to "No compatible VLESS profiles")) {
                startImport(key)
                await { texts().any { it.startsWith(prefix) } }
                image("rejected-$key")
                click("Cancel"); await { "Subscription details" in texts() }; back(); await { "Choose" in texts() }
                assertTrue("Rejected import kept selected endpoint", "Mixed XHTTP" in texts())
                mark("Rejected $key input without replacing saved access")
            }
            import("query")
            assertTrue("Subscription · 7" in texts())
            assertTrue("Remote update remains available", "Update" in texts())
            choose("Alternative · XHTTP"); traffic("query-xhttp")
            import("subscription3")
            assertTrue("Canonical subscription retained selected XHTTP", "Alternative · XHTTP" in texts())
            shell("am force-stop vikify.vpn"); shell("am start -W -n vikify.vpn/vikify.vpn.ui.MainActivity")
            await { "Choose" in texts() && "Alternative · XHTTP" in texts() }
            assertTrue("Ready to connect" in texts())
            image("restored-subscription3")
            mark("Canonical subscription 3 restored; XHTTP persisted after process restart; VPN off")
        } finally { disconnect() }
    }

    private fun startImport(key: String) {
        click("Details"); click("Replace link")
        await { nodes(root()).any { it.className == "android.widget.EditText" } }
        val editor = nodes(root()).single { it.className == "android.widget.EditText" }
        assertTrue("Fill import form", editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fixtures.getString(key))
        }))
        click("Replace")
    }

    private fun auditConsent() {
        choose("Alternative · XHTTP")
        click("Connect"); await { "Allow Vikify to route your traffic?" in texts() }
        click("Cancel"); await { "Ready to connect" in texts() && "Connect" in texts() }
        click("Connect"); await { "Allow Vikify to route your traffic?" in texts() }
        click("Privacy policy"); await { texts().any { it.contains("VictoriaV publishes") } }
        click("Close"); click("Agree and connect")
        systemButton("android:id/button2")
        await { "Ready to connect" in texts() && "Connect" in texts() }
        click("Connect"); systemButton("android:id/button1")
        await(65_000) {
            allowNotificationsIfRequested()
            "VPN connected" in texts()
        }
        TunnelTrafficTest().apply {
            defaultNetworkCarriesDnsAndHttpsThroughVpn()
            defaultHttpsStackDownloadsARealPage()
            separateHttpsHostReturnsAnExitAddress()
        }
        image("consent-connected"); disconnect()
        mark("Consent cancellation, offline privacy, acceptance, VPN denial/retry and traffic PASS")
    }

    private fun systemButton(id: String) {
        await { nodes(ui.rootInActiveWindow).any { it.viewIdResourceName == id } }
        val button = nodes(ui.rootInActiveWindow).single { it.viewIdResourceName == id }
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun allowNotificationsIfRequested() {
        val current = nodes(ui.rootInActiveWindow)
        if (current.none { it.text?.toString()?.contains("notifications", ignoreCase = true) == true }) return
        val allow = current.firstOrNull {
            it.viewIdResourceName?.endsWith("/permission_allow_button") == true &&
                it.packageName?.toString()?.contains("permissioncontroller") == true
        } ?: return
        SystemClock.sleep(600)
        assertTrue("Allow notification permission requested after VPN approval",
            allow.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        mark("First-install notification permission accepted")
    }
    private fun import(key: String) {
        startImport(key); await(60_000) { "Subscription details" in texts() }
        back(); await { "Choose" in texts() }
        mark("Imported $key through canonical release UI")
    }
    private fun choose(name: String) {
        click("Choose"); await { name in texts() }
        val row = nodes(root()).single { it.isCheckable && nodes(it).any { n -> n.text?.toString() == name } }
        if (row.isChecked) back() else click(name)
        await { "Choose" in texts() && name in texts() }
    }
    private fun traffic(tag: String) {
        click("Connect")
        await(65_000) {
            check("Connection failed" !in texts()) { "Connection failed for $tag" }
            "VPN connected" in texts()
        }
        TunnelTrafficTest().apply {
            defaultNetworkCarriesDnsAndHttpsThroughVpn()
            defaultHttpsStackDownloadsARealPage()
            separateHttpsHostReturnsAnExitAddress()
        }
        await { "VPN connected" in texts() && "Disconnect" in texts() }
        image(tag);disconnect();await { "Ready to connect" in texts() }
        mark("Traffic PASS: $tag (DNS, HTTPS 204, document and exit address)")
    }
    private fun disconnect() {
        // An accessibility tree can briefly be empty while the window updates.
        // Do not treat that single snapshot as an already disconnected VPN.
        val end = SystemClock.elapsedRealtime() + 2_000
        do {
            val current = texts()
            if ("Disconnect" in current) { click("Disconnect");await { "Ready to connect" in texts() };return }
            if ("Ready to connect" in current) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
    }
    private fun mark(value: String) = ins.sendStatus(2, Bundle().apply { putString("auditStep", value) })
    private fun root() = ui.rootInActiveWindow?.takeIf { it.packageName?.toString() == "vikify.vpn" }
        ?: ui.windows.sortedByDescending { it.layer }.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == "vikify.vpn" }
    private fun nodes(n: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (n != null) { yield(n);for (i in 0 until n.childCount) yieldAll(nodes(n.getChild(i))) }
    }
    private fun texts() = nodes(root()).mapNotNull { it.text?.toString() }.toSet()
    private fun click(label: String) {
        // A new dialog may still expose the preceding screen's tree. Let its
        // first layout settle before deciding that the target needs scrolling.
        SystemClock.sleep(600)
        for (attempt in 0 until 12) {
            if (nodes(root()).any { it.text?.toString() == label && it.isVisibleToUser }) break
            val scroller = nodes(root()).lastOrNull { it.isScrollable && it.isVisibleToUser }
            if (scroller != null) {
                val bounds = Rect().also(scroller::getBoundsInScreen)
                shell("input swipe ${bounds.centerX()} ${bounds.top + bounds.height()*3/4} ${bounds.centerX()} ${bounds.top + bounds.height()/3} 350")
            }
            // Wait for the fling to settle before deriving a tap from moving bounds.
            SystemClock.sleep(750)
        }
        await { nodes(root()).any { it.text?.toString() == label && it.isVisibleToUser } }
        // Text replacement can resize a dialog after the new button node appears.
        // Derive a single tap only after its on-screen bounds stop moving.
        SystemClock.sleep(350)
        fun bounds() = Rect().also(nodes(root()).single {
            it.text?.toString() == label && it.isVisibleToUser
        }::getBoundsInScreen)
        var r = bounds()
        var stableSince = SystemClock.elapsedRealtime()
        val deadline = stableSince + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            val latest = bounds()
            val now = SystemClock.elapsedRealtime()
            if (latest != r) { r = latest; stableSince = now }
            else if (now - stableSince >= 300) break
        }
        check(!r.isEmpty)
        mark("Tap $label at $r")
        shell("input tap ${r.centerX()} ${r.centerY()}");SystemClock.sleep(150)
    }
    private fun back() { shell("input keyevent 4");SystemClock.sleep(150) }
    private fun shell(s: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(s)).use { it.readBytes() }
    private fun await(timeout: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do { if (condition()) return;SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < end)
        error("Timed out waiting for release UI")
    }
    private fun image(name: String) {
        ui.takeScreenshot()?.let { bitmap ->
            File(ins.targetContext.filesDir, "external-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
