package vikify.vpn.subscription

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vikify.vpn.R
import vikify.vpn.BuildConfig
import vikify.vpn.ui.MainActivity
import java.io.File
import java.net.URI

/** Opt-in only. The host streams the account fixture to private storage on the QA installation. */
class LiveImportAuditTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext

    @Test fun importedAccount() {
        val source = InstrumentationRegistry.getArguments().getString("source")
        assumeTrue((context.packageName.endsWith(".uiqa") || BuildConfig.EMULATOR_TESTING) && source in listOf("clipboard", "verify"))
        val expectedProfiles = InstrumentationRegistry.getArguments().getString("expectedProfiles")?.toInt() ?: 7
        val fixture = JSONObject(File(context.filesDir, "qa-import.json").readText())
        val link = fixture.getString("url")
        if (source == "clipboard") {
            assertTrue(context.subscriptions.state.value.profiles.isEmpty())
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click(context.getString(R.string.add_subscription))
            node { it.className == "android.widget.EditText" }
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val previous = clipboard.primaryClip
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("Vikify QA", link))
                click(context.getString(R.string.paste))
                node { it.text?.toString() == context.getString(R.string.import_source, URI(link).rawAuthority) }
                assertTrue("Preview must not download or store access", context.subscriptions.state.value.profiles.isEmpty())
                assertFalse("Preview exposed the subscription token",
                    nodes(instrument.uiAutomation.rootInActiveWindow).any { it.text?.contains(fixture.getString("url")) == true })
                click(context.getString(R.string.add))
                node { it.text?.toString() == context.getString(R.string.subscription_count, expectedProfiles) }
            } finally {
                if (previous != null) clipboard.setPrimaryClip(previous)
                else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        val saved = context.subscriptions.state.value
        assertTrue("Wrong saved subscription", saved.snapshot?.url == link)
        assertEquals(expectedProfiles, saved.profiles.size)
        assertTrue("Wrong profile credentials", saved.profiles.all { it.uuid.equals(fixture.getString("uuid"), true) })
        assertTrue("No saved selection", saved.selectedProfile != null)
    }

    @Test fun rejectedClipboardKeepsDraft() {
        assumeTrue((context.packageName.endsWith(".uiqa") || BuildConfig.EMULATOR_TESTING) &&
            InstrumentationRegistry.getArguments().getString("source") == "clipboardErrors")
        val saved = context.subscriptions.state.value
        assertNotNull(saved.snapshot)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click(context.getString(R.string.details))
        click(context.getString(R.string.replace_link))
        val draft = "draft.example"
        val editor = node { it.className == "android.widget.EditText" }
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
        }))
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val previous = clipboard.primaryClip
        fun rejected(clip: ClipData, message: Int) {
            clipboard.setPrimaryClip(clip)
            android.os.SystemClock.sleep(250)
            assertEquals("The test device replaced the clipboard before Paste", clip.itemCount,
                clipboard.primaryClip?.itemCount)
            click(context.getString(R.string.paste))
            node { it.text?.toString() == context.getString(message) }
            node { it.className == "android.widget.EditText" && it.text?.toString() == draft }
        }
        try {
            rejected(ClipData.newPlainText("", ""), R.string.clipboard_empty)
            rejected(ClipData.newPlainText("", "first").apply { addItem(ClipData.Item("second")) }, R.string.clipboard_many)
            rejected(ClipData.newPlainText("", "vless://sample"), R.string.subscription_error_format)
            rejected(ClipData.newPlainText("", "http://test.example/api?token=example"),
                R.string.subscription_error_url)
            click(context.getString(R.string.cancel))
            assertEquals(saved.selectedId, context.subscriptions.state.value.selectedId)
            val current = requireNotNull(context.subscriptions.state.value.snapshot)
            // A scheduled refresh may legitimately advance updatedAt while this UI is open.
            // Rejected clipboard input must preserve every access field and the selection.
            assertEquals(saved.snapshot?.copy(updatedAt = current.updatedAt), current)
        } finally {
            if (previous != null) clipboard.setPrimaryClip(previous)
            else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun nodes(n: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (n == null) return@sequence
        yield(n)
        for (i in 0 until n.childCount) yieldAll(nodes(n.getChild(i)))
    }

    private fun node(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 30_000
        do {
            nodes(instrument.uiAutomation.rootInActiveWindow).firstOrNull(match)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < end)
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir("ui-audit"), "import-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        error("Expected import UI state did not appear")
    }

    private fun click(text: String) {
        var n = node { it.text?.toString() == text }
        while (!n.isClickable && n.parent != null) n = n.parent
        assertTrue("Could not activate import control", n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
