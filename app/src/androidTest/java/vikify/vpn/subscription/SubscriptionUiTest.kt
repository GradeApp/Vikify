package vikify.vpn.subscription

import android.graphics.Bitmap
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vikify.vpn.R
import vikify.vpn.core.SecurePrefs
import vikify.vpn.ui.MainActivity
import java.io.File

/** Exercises real Compose controls and encrypted storage with local, non-working credentials. */
@RunWith(AndroidJUnit4::class)
class SubscriptionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun windowRoot(): AccessibilityNodeInfo? = automation.rootInActiveWindow
        ?: automation.windows.sortedByDescending { it.layer }
            .mapNotNull { it.root }.firstOrNull { it.packageName == context.packageName }

    @Test fun selectProfileRejectBadReplacementAndRemoveAccess() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val landscape = InstrumentationRegistry.getArguments().getString("rotation") == "landscape"
        val body = listOf(443, 8443, 8445).map { port ->
            "vless://aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee@edge.example:$port?type=tcp&security=reality&sni=www.apple.com&fp=qq&pbk=${"A".repeat(43)}&sid=aabb&flow=xtls-rprx-vision#REALITY-$port"
        }.plus(listOf("xhttp", "ws").map { type ->
            "vless://aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee@web.example:443?type=$type&security=tls&sni=web.example&fp=chrome&host=web.example&path=%2Fcdn%2Fmedia#${type.uppercase()}"
        }).joinToString("\n")
        val profiles = VlessParser.parseSubscription(body)
        SecurePrefs(context).saveSubscription(JSONObject()
            .put("url", "https://subscription.example/sub/abcdefghijklmnop")
            .put("body", body).put("selectedId", profiles.first().id)
            .put("updatedAt", System.currentTimeMillis()).toString())
        try {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            find(context.getString(R.string.status_disconnected))
            if (landscape) {
                // The launcher's portrait policy can override a rotation made
                // before the app owns the display. Rotate the resumed app.
                assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
                await { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            }
            instrumentation.waitForIdleSync()
            capture("profiles-home")
            val homeLabels = flatten(windowRoot()).mapNotNull { it.text?.toString() }
            assertFalse("Home must not expose endpoint or subscription addresses",
                homeLabels.any { "edge.example" in it || "subscription.example" in it })
            if (!landscape && context.resources.configuration.fontScale == 1f &&
                context.resources.configuration.screenHeightDp >= 600) {
                val frame = requireNotNull(automation.takeScreenshot())
                val viewport = android.graphics.Rect(0, 0, frame.width, frame.height)
                frame.recycle()
                for (label in listOf(R.string.connect, R.string.choose_profile, R.string.refresh_subscription, R.string.details)) {
                    val text = context.getString(label)
                    val node = awaitNode { it.text?.toString() == text &&
                        !android.graphics.Rect().also(it::getBoundsInScreen).isEmpty }
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    assertTrue("$text ($bounds) must fit in $viewport without scrolling", viewport.contains(bounds))
                }
            }
            click(context.getString(R.string.choose_profile))
            find("REALITY-8443")
            capture("profiles-list")
            click("XHTTP")
            await { context.subscriptions.state.value.selectedProfile?.name == "XHTTP" }
            click(context.getString(R.string.details))
            click(context.getString(R.string.replace_link))
            awaitNode { it.className == "android.widget.EditText" }
            // Read coordinates after the new dialog's entry layout settles,
            // just as click() does for text buttons. A first layout can move.
            Thread.sleep(600)
            val editor = awaitNode { it.className == "android.widget.EditText" }
            var editorBounds = android.graphics.Rect().also(editor::getBoundsInScreen)
            var editorStable = android.os.SystemClock.uptimeMillis()
            val editorDeadline = editorStable + 5_000
            while (android.os.SystemClock.uptimeMillis() < editorDeadline) {
                Thread.sleep(100)
                val latest = android.graphics.Rect().also(
                    awaitNode { it.className == "android.widget.EditText" }::getBoundsInScreen)
                val now = android.os.SystemClock.uptimeMillis()
                if (latest != editorBounds) { editorBounds = latest; editorStable = now }
                else if (now - editorStable >= 300) break
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("uiClick", "Editor $editorBounds") })
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "input tap ${editorBounds.centerX()} ${editorBounds.centerY()}" )).use { it.readBytes() }
            await { flatten(windowRoot()).any { it.className == "android.widget.EditText" && it.isFocused } }
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "http://invalid.example/sub/abcdefghijklmnop")
            })
            // Samsung's keyboard can appear after the first layout pass. Wait
            // for the real IME so this check actually exercises IME insets.
            await {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("dumpsys input_method"))
                    .bufferedReader().use { it.readText() }.contains("mInputShown=true")
            }
            Thread.sleep(500)
            capture("import-keyboard")
            run {
                Thread.sleep(2000)
                capture("import-keyboard-settled")
                val field = flatten(windowRoot()).firstOrNull {
                    it.className == "android.widget.EditText" && it.isVisibleToUser &&
                        !android.graphics.Rect().also(it::getBoundsInScreen).isEmpty
                }
                assertNotNull("Focused input must remain visible after the keyboard settles", field)
            }
            if (landscape && android.os.Build.VERSION.SDK_INT >= 30) {
                // Exercise the keyboard's Done action on short screens. The
                // portrait case separately exercises the visible Replace button.
                val focused = awaitNode { it.className == "android.widget.EditText" && it.isFocused }
                assertTrue("Keyboard Done submits the form", focused.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            } else click(context.getString(R.string.replace))
            find(context.getString(R.string.subscription_error_url))
            await {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("dumpsys input_method"))
                    .bufferedReader().use { it.readText() }.contains("mInputShown=false")
            }
            capture("invalid-link")
            assertEquals(profiles[3].id, context.subscriptions.state.value.selectedId)
            click(context.getString(R.string.cancel))
            click(context.getString(R.string.remove_access))
            click(context.getString(R.string.remove))
            await { context.subscriptions.state.value.profiles.isEmpty() }
            find(context.getString(R.string.add_subscription))
            assertNull(SecurePrefs(context).getSubscription())
        } finally {
            SecurePrefs(context).removeAccess()
            SubscriptionRefreshJob.cancel(context)
            if (landscape) automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir("ui-audit"), "$name.png")
        if (name == "failure") {
            val root = windowRoot()
            File(file.parentFile, "failure-tree.txt").writeText("root=${root?.packageName}\n" +
                flatten(root).joinToString("\n") { "${it.className}: ${it.text}" })
        }
        automation.takeScreenshot()?.let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (InstrumentationRegistry.getArguments().getString("rotation") == "landscape") {
                assertTrue("Landscape screenshot must be wider than tall", bitmap.width > bitmap.height)
            }
            bitmap.recycle()
        }
    }

    private fun flatten(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { flatten(node.getChild(it)) }

    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        capture("failure")
        fail("UI did not reach the expected state")
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = flatten(windowRoot()).firstOrNull(predicate); result != null }
        return checkNotNull(result)
    }

    private fun find(text: String): AccessibilityNodeInfo = awaitNode { it.text?.toString() == text }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = null
        for (attempt in 0 until 16) {
            val root = windowRoot()
            val window = android.graphics.Rect().also { root?.getBoundsInScreen(it) }
            automation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { ime ->
                    val bounds = android.graphics.Rect().also(ime::getBoundsInScreen)
                    if (!bounds.isEmpty) window.bottom = minOf(window.bottom, bounds.top)
                }
            fun visible(candidate: AccessibilityNodeInfo): Boolean {
                val bounds = android.graphics.Rect().also(candidate::getBoundsInScreen)
                return !bounds.isEmpty && android.graphics.Rect.intersects(bounds, window)
            }
            node = flatten(root).firstOrNull { it.text?.toString() == text && visible(it) }
            if (node != null) break
            // Scroll the actual dialog/list. Screen-based swipes can land on
            // the keyboard, type characters, or miss after device rotation.
            val scroller = flatten(root).firstOrNull { it.className == "android.widget.ScrollView" && it.isScrollable && visible(it) }
                ?: break
            val bounds = android.graphics.Rect().also(scroller::getBoundsInScreen)
            if (!bounds.intersect(window)) break
            if (bounds.width() <= 0 || bounds.height() <= 0) break
            // Keep the gesture in the container padding, outside EditText's
            // own scrolling and entirely above the IME on edge-to-edge Android.
            val x = bounds.left + minOf(12, bounds.width() / 4)
            val start = bounds.top + bounds.height() * 3 / 4
            val end = bounds.top + bounds.height() * 2 / 5
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("input swipe $x $start $x $end 300"))
                .use { it.readBytes() }
            Thread.sleep(750)
        }
        // Reachability updates can replace Compose accessibility node IDs.
        // Exercise the same pointer path as a user, using the current UI bounds.
        var bounds = android.graphics.Rect().also((node ?: find(text))::getBoundsInScreen)
        var stableSince = android.os.SystemClock.uptimeMillis()
        val deadline = stableSince + 5_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            val latest = android.graphics.Rect().also(find(text)::getBoundsInScreen)
            val now = android.os.SystemClock.uptimeMillis()
            if (latest != bounds) { bounds = latest; stableSince = now }
            else if (now - stableSince >= 300) break
        }
        assertFalse("Control has no visible bounds: $text", bounds.isEmpty)
        instrumentation.sendStatus(2, Bundle().apply { putString("uiClick", "$text $bounds") })
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "input tap ${bounds.centerX()} ${bounds.centerY()}" )).use { it.readBytes() }
        Thread.sleep(1000)
    }
}
