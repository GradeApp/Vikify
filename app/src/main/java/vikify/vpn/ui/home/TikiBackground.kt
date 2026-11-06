package vikify.vpn.ui.home

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import vikify.vpn.R
import kotlin.math.cos
import kotlin.math.sin

private const val FlightTickMillis = 30L

@Composable
internal fun LagoonBackdrop(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.lagoon), null, modifier, contentScale = ContentScale.Crop)
}

// Only the hummingbird consults these bounds. Fireflies use the entire visible window.
private val LocalBirdObstacles = staticCompositionLocalOf<MutableMap<Any, Rect>> { mutableMapOf() }

internal fun Modifier.birdClearance(): Modifier = composed {
    val obstacles = LocalBirdObstacles.current
    val key = remember { Any() }
    DisposableEffect(obstacles, key) { onDispose { obstacles.remove(key) } }
    onGloballyPositioned { obstacles[key] = it.boundsInRoot() }
}

@Composable
internal fun FlightScene(active: Boolean, stage: Int, content: @Composable BoxScope.() -> Unit) {
    val obstacles = remember { mutableStateMapOf<Any, Rect>() }
    CompositionLocalProvider(LocalBirdObstacles provides obstacles) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (active && stage > 0 && LocalWindowInfo.current.isWindowFocused) {
                ForegroundFlights(obstacles, stage)
            }
        }
    }
}

@Composable
private fun motionScale(): Float {
    val resolver = LocalContext.current.contentResolver
    fun readScale() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember(resolver) { mutableFloatStateOf(readScale()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readScale() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        scale = readScale()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

@Composable
private fun ForegroundFlights(obstacles: Map<Any, Rect>, stage: Int) {
    val scale = motionScale()
    if (scale <= 0f) return
    val owner = LocalLifecycleOwner.current
    // Paths and firefly brightness share a clock, separate from wing animation.
    val time = produceState(SystemClock.uptimeMillis(), owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                value = now
                delay(FlightTickMillis - now % FlightTickMillis)
            }
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().clipToBounds(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (w <= 0f || h <= 0f) return@BoxWithConstraints
        fun seconds() = time.value / (1000.0 * scale)
        val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
        if (!keyboardOpen && maxHeight >= 140.dp) {
            val safe = WindowInsets.safeDrawing
            val direction = LocalLayoutDirection.current
            val left = safe.getLeft(density, direction).toFloat()
            val top = safe.getTop(density).toFloat()
            val bounds = Rect(left, top, w - safe.getRight(density, direction), h - safe.getBottom(density))
            val side = 72.dp
            val pixels = with(density) { side.roundToPx().toFloat() }
            val margin = with(density) { 4.dp.toPx() }
            val space by remember(obstacles, bounds, pixels, margin) {
                derivedStateOf { hummingbirdAirspace(bounds, obstacles.values, pixels, margin) }
            }
            space?.let { area ->
                Hummingbird(Modifier.size(side).graphicsLayer {
                    val t = seconds()
                    val phase = t * .44
                    translationX = area.left + (area.width - pixels) * wave(phase)
                    translationY = area.top + (area.height - pixels) * (.12f + .76f * wave(t * .71 + 1.4))
                    // Turn at the end of a hover without flattening the sprite.
                    scaleX = if (cos(phase) >= 0.0) 1f else -1f
                }, motionScale = scale)
            }
        }
        if (stage >= 2) repeat(4) { index ->
            val diameter = (13 + 2 * (index % 2)).dp
            val px = with(density) { diameter.toPx() }
            // Drawn last, above the hummingbird and app surfaces. No input or semantics.
            Spacer(Modifier.size(diameter).graphicsLayer {
                val t = seconds()
                translationX = (w - px).coerceAtLeast(0f) * wave(t * (0.16 + index * 0.027) + index * 1.79)
                translationY = (h - px).coerceAtLeast(0f) * wave(t * (0.23 + index * 0.021) + index * 2.31)
                alpha = 0.60f + 0.30f * wave(t * 1.2 + index)
            }.drawWithCache {
                val halo = Brush.radialGradient(listOf(Color(0xD5FFF1AB), Color(0x44F9CF69), Color.Transparent))
                onDrawBehind {
                    drawCircle(halo)
                    drawCircle(Color(0xFFFFF3C8), radius = size.minDimension * 0.11f)
                }
            })
        }
    }
}

private fun wave(phase: Double) = (0.5 + 0.5 * sin(phase)).toFloat()
