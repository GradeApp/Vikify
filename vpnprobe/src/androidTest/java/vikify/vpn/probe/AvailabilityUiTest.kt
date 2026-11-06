package vikify.vpn.probe

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvailabilityUiTest {
    @Test fun profileStatesAndSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("availability")
        assumeNotNull(mode)
        require(mode in listOf("settled", "unknown", "expire"))
        if (mode == "expire") SystemClock.sleep(62_000)
        val end = SystemClock.uptimeMillis() + 35_000
        var rows = emptyList<AccessibilityNodeInfo>()
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            rows = if (root?.packageName == "vikify.vpn") nodes(root).filter { it.isCheckable && it.isVisibleToUser }.toList() else emptyList()
            val statuses = rows.map { it.stateDescription?.toString().orEmpty() }
            val ready = rows.size == 7 && if (mode == "settled") statuses.all { it.startsWith("Potentially available") || it.startsWith("Server unavailable") }
                else statuses.all { it.startsWith("Availability unknown") }
            if (ready) break
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < end)
        assertEquals("All seven rows fit", 7, rows.size)
        val selected = rows.filter { it.isChecked }
        assertEquals(1, selected.size)
        assertTrue("Stored XHTTP selection", nodes(selected.single()).any { it.text?.toString() == "Alternative · XHTTP" })
        val statuses = rows.map { it.stateDescription?.toString().orEmpty() }
        if (mode == "settled") {
            assertTrue(statuses.all { it.startsWith("Potentially available") || it.startsWith("Server unavailable") })
            assertTrue("XHTTP endpoint reachable", selected.single().stateDescription.toString().startsWith("Potentially available"))
        } else assertTrue(statuses.all { it.startsWith("Availability unknown") })
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", rows.joinToString("\n") { row ->
                nodes(row).mapNotNull { it.text?.toString() }.joinToString(" / ") + " = " + row.stateDescription
            })
        })
    }

    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null) return@sequence
        yield(node)
        for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i)))
    }
}
