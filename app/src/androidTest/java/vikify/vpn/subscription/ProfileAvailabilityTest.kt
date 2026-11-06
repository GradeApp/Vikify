package vikify.vpn.subscription

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vikify.vpn.R
import vikify.vpn.BuildConfig
import vikify.vpn.core.SecurePrefs
import vikify.vpn.ui.MainActivity
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Isolated installation only. Closed and listening local ports require no production server changes. */
@RunWith(AndroidJUnit4::class)
class ProfileAvailabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun realSocketsShowGreenAndRedWithoutSendingPayloadOrChangingSelection() {
        check(context.packageName.endsWith(".uiqa") || BuildConfig.EMULATOR_TESTING) { "Requires isolated QA package or emulator build" }
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val closed = Socket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val bytes = CopyOnWriteArrayList<Int>()
        val worker = thread(isDaemon = true) {
            try {
                while (!listener.isClosed) listener.accept().use {
                    it.soTimeout = 5_000
                    bytes.add(it.getInputStream().read())
                }
            } catch (_: java.io.IOException) { }
        }
        fun link(port: Int, type: String, name: String) =
            "vless://aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee@127.0.0.1:$port" +
                "?type=$type&security=tls&sni=test.example&path=%2F#${name.replace(" ", "%20")}"
        val body = listOf(link(listener.localPort, "xhttp", "Available XHTTP"),
            link(listener.localPort, "ws", "Available WebSocket"),
            link(closed.localPort, "xhttp", "Unavailable test")).joinToString("\n")
        val profiles = VlessParser.parseSubscription(body)
        val selected = profiles.first().id
        try {
            SecurePrefs(context).saveSubscription(JSONObject()
                .put("url", "https://subscription.example/sub/abcdefghijklmnop")
                .put("body", body).put("updatedAt", System.currentTimeMillis()).put("selectedId", selected).toString())
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click(context.getString(R.string.choose_profile))
            await {
                status("Available XHTTP") == context.getString(R.string.availability_reachable) &&
                    status("Available WebSocket") == context.getString(R.string.availability_reachable) &&
                    status("Unavailable test") == context.getString(R.string.availability_unreachable)
            }
            await { bytes.size == 1 }
            assertEquals(listOf(-1), bytes.toList())
            assertEquals(selected, context.subscriptions.state.value.selectedId)
            capture("green-red")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            click(context.getString(R.string.choose_profile))
            await { status("Available XHTTP") != null }
            assertEquals(context.getString(R.string.availability_reachable), status("Available XHTTP"))
            assertEquals(1, bytes.size)
            click(context.getString(R.string.availability_check))
            await { bytes.size == 2 && status("Unavailable test") == context.getString(R.string.availability_unreachable) }
            assertEquals(listOf(-1, -1), bytes.toList())
            assertEquals(selected, context.subscriptions.state.value.selectedId)
        } finally {
            listener.close();closed.close();worker.join(1_000)
            SecurePrefs(context).removeAccess();SubscriptionRefreshJob.cancel(context)
        }
    }

    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null) return@sequence
        yield(node)
        for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i)))
    }
    private fun root() = automation.rootInActiveWindow?.takeIf { it.packageName == context.packageName }
    private fun status(name: String): String? = nodes(root()).firstOrNull { row ->
        row.isCheckable && nodes(row).any { it.text?.toString() == name }
    }?.let { AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString() }
    private fun click(text: String) {
        var target: AccessibilityNodeInfo? = null
        await { target = nodes(root()).firstOrNull { it.text?.toString() == text && it.isVisibleToUser }; target != null }
        var node = requireNotNull(target)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun await(predicate: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 20_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(100)
        }
        capture("failure")
        fail("Availability UI did not reach expected state")
    }
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir("availability-qa"), "$name.png")
        val image = requireNotNull(automation.takeScreenshot())
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
