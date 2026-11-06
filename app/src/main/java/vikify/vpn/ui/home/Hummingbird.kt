package vikify.vpn.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import vikify.vpn.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val WingbeatMillis = 50.0
private const val WingSweepDegrees = 53f

@Composable
internal fun Hummingbird(modifier: Modifier, motionScale: Float) {
    val sheet = ImageBitmap.imageResource(R.drawable.hummingbird_flight)
    val cell = IntSize(sheet.width / 2, sheet.height)
    val owner = LocalLifecycleOwner.current
    val phase = produceState(0f, owner, motionScale) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                withFrameMillis { frameTime ->
                    val cycle = (frameTime / (WingbeatMillis * motionScale)) % 1.0
                    value = (cycle * 2.0 * PI).toFloat()
                }
            }
        }
    }
    Canvas(modifier) {
        val destination = size.toIntSize()
        // The two atlas cells share a shoulder at (130, 110) on a 256 px artboard.
        val shoulder = Offset(size.width * (130f / 256f), size.height * (110f / 256f))
        val angle = phase.value
        val depth = cos(angle)
        // Use the transparent space ahead of the bill to keep the longer wingtip in frame.
        translate(left = size.width * (17f / 256f)) {
            drawImage(sheet, srcSize = cell, dstSize = destination, filterQuality = FilterQuality.Medium)
            withTransform({
                rotate(8f + (WingSweepDegrees / 2f) * sin(angle), shoulder)
                scale(1.243f * (.93f + .07f * depth), 1.13f * (.91f - .09f * depth), shoulder)
            }) {
                drawImage(sheet, srcOffset = IntOffset(cell.width, 0), srcSize = cell,
                    dstSize = destination, filterQuality = FilterQuality.Medium)
            }
        }
    }
}

// Find the largest clear rectangle whenever the layout changes. This also works
// with staggered cards in landscape or a dialog across the middle of the window.
internal fun hummingbirdAirspace(bounds: Rect, obstacles: Collection<Rect>, side: Float, margin: Float): Rect? {
    val window = bounds.deflate(margin)
    if (window.width < side || window.height < side) return null
    val blocks = obstacles.filterNot { it.isEmpty }.map { it.inflate(margin).intersect(window) }
        .filterNot { it.isEmpty }.sortedBy { it.top }
    val edges = (listOf(window.left, window.right) + blocks.flatMap { listOf(it.left, it.right) }).distinct().sorted()
    var best: Rect? = null
    var bestArea = 0f
    fun consider(left: Float, top: Float, right: Float, bottom: Float) {
        val area = (right - left) * (bottom - top)
        if (bottom - top >= side && area > bestArea) {
            best = Rect(left, top, right, bottom)
            bestArea = area
        }
    }
    for ((i, left) in edges.withIndex()) {
        for (j in i + 1 until edges.size) {
            val right = edges[j]
            if (right - left < side) continue
            var top = window.top
            for (block in blocks) {
                if (block.right <= left || block.left >= right) continue
                consider(left, top, right, block.top)
                top = maxOf(top, block.bottom)
            }
            consider(left, top, right, window.bottom)
        }
    }
    return best
}
