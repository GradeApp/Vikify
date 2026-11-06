package vikify.vpn.ui.home

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HummingbirdAirspaceTest {
    @Test fun portraitCardsLeaveAnUninterruptedPathBelowControls() {
        val bounds = Rect(0f, 20f, 360f, 720f)
        val cards = listOf(Rect(10f, 25f, 350f, 90f), Rect(10f, 95f, 350f, 300f))
        assertEquals(Rect(4f, 304f, 356f, 716f), hummingbirdAirspace(bounds, cards, 72f, 4f))
    }

    @Test fun landscapeCanUseSpaceBelowTheShorterColumn() {
        val bounds = Rect(0f, 0f, 640f, 280f)
        val cards = listOf(Rect(0f, 0f, 310f, 70f), Rect(320f, 0f, 640f, 260f))
        assertEquals(Rect(4f, 74f, 316f, 276f), hummingbirdAirspace(bounds, cards, 72f, 4f))
    }

    @Test fun aFullDialogOrTinyWindowDoesNotPutTheBirdOverText() {
        val bounds = Rect(0f, 0f, 320f, 240f)
        assertNull(hummingbirdAirspace(bounds, listOf(bounds), 72f, 4f))
        assertNull(hummingbirdAirspace(Rect(0f, 0f, 50f, 90f), emptyList(), 72f, 4f))
    }
}
