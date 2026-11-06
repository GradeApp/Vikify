package vikify.vpn.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run against an open Vikify profile list, with an explicit -e profile NAME. */
@RunWith(AndroidJUnit4::class)
class ProfilePickerTest {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private lateinit var target: String

    @Before fun requireTarget() {
        val name = InstrumentationRegistry.getArguments().getString("profile")
        assumeNotNull(name)
        target = requireNotNull(name)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    @Test fun selectProfileOnce() {
        val row = targetRow()
        assertFalse("Start with a different profile selected", row.isChecked)
        assertTrue("The target row is no longer actionable", activate(row))
        awaitHome(target)
    }

    @Test fun dismissedRowCannotBeActivated() {
        val row = targetRow()
        assertFalse(row.isChecked)
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        awaitHome()
        assertFalse("A detached row must never trigger another click", activate(row))
        assertFalse(nodes(root()).any { it.text?.toString() == target })
    }

    /** Separately exercise the touch path a person uses on the visible label. */
    @Test fun titleRespondsToOneTap() {
        val row = targetRow()
        assertFalse("Start with a different profile selected", row.isChecked)
        val label = nodes(row).single { it.text?.toString() == target }
        val bounds = Rect().also(label::getBoundsInScreen)
        assertFalse(bounds.isEmpty)
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "input tap ${bounds.centerX()} ${bounds.centerY()}"
        )).use { it.readBytes() }
        awaitHome(target)
    }

    private fun root(): AccessibilityNodeInfo? {
        val active = automation.rootInActiveWindow ?: automation.windows.firstOrNull { it.isActive }?.root
        return active?.takeIf { it.packageName?.toString() == "vikify.vpn" }
    }

    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null) return@sequence
        yield(node)
        for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i)))
    }

    private fun matches(row: AccessibilityNodeInfo) =
        row.isVisibleToUser && row.isCheckable && row.isClickable &&
            nodes(row).any { it.text?.toString() == target }

    private fun targetRow(): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            nodes(root()).singleOrNull(::matches)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        val current = root()
        val rows = nodes(current).filter { it.isCheckable }.map {
            "clickable=${it.isClickable} visible=${it.isVisibleToUser} target=${nodes(it).any { n -> n.text?.toString() == target }}"
        }.toList()
        error("Target row unavailable in ${current?.packageName}; checkable rows: $rows")
    }

    // Dispatch to this live accessibility node. Never fall back to screen coordinates.
    private fun activate(row: AccessibilityNodeInfo) =
        row.refresh() && matches(row) && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    private fun awaitHome(selected: String? = null) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            val labels = nodes(root()).mapNotNull { it.text?.toString() }.toSet()
            if ("VIKIFY" in labels && (selected == null || selected in labels)) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Did not reach Vikify home with the expected profile")
    }
}
