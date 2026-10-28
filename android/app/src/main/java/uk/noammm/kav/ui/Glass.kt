package uk.noammm.kav.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

val LocalLiquidBackdrop = staticCompositionLocalOf<LiquidBackdrop?> { null }

// AGSL needs Android 13.
val liquidGlassReady get() = Build.VERSION.SDK_INT >= 33

@Composable
fun rememberLiquidBackdrop(): LiquidBackdrop? =
    if (liquidGlassReady) rememberLiquidLayer() else null

fun Modifier.glassBackdrop(liquid: LiquidBackdrop?): Modifier =
    if (K.liquid && liquid != null) liquidSource(liquid) else this

// A floating pane's edge in glass: light catching its top and fading down its sides, never a full white ring.
private fun glassEdge(): Brush =
    if (K.light) SolidColor(K.text.copy(alpha = .14f))
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), K.separator, K.separator))

private val paneFill get() = K.surfaceRaised.copy(alpha = .62f)

// The floating layer: the tab bar, buttons over a map or a list, a field that stays on top. Glass where it's chosen.
@Composable
fun Modifier.glassSurface(radius: Dp = 22.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    if (!K.liquid) return clip(shape).background(K.surfaceRaised).border(0.5.dp, K.separator, shape)
    val liquid = LocalLiquidBackdrop.current
    val pane = if (liquid != null) liquidPane(liquid, radius) else clip(shape).background(paneFill)
    return pane.border(1.dp, glassEdge(), shape)
}

// A card on the page: its own shade and a hairline. solid: one that floats over others, a sheet or a menu.
fun Modifier.panel(radius: Dp = K.rCard, solid: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(if (solid) K.surfaceRaised else K.surface).border(0.5.dp, K.separator, shape)
}

@Composable
fun ScrollEdge(scrolled: Boolean, height: Dp = 28.dp) {
    val shown by animateFloatAsState(if (scrolled) 1f else 0f, tween(180), label = "scrollEdge")
    if (shown > 0f) Box(
        Modifier.fillMaxWidth().height(height).graphicsLayer { alpha = shown }
            .background(Brush.verticalGradient(listOf(K.bg, K.bg.copy(alpha = 0f)))),
    )
}

@Composable
fun FloatingTop(
    top: @Composable ColumnScope.() -> Unit,
    estimate: Dp = 64.dp,
    fade: Dp = 0.dp,
    content: @Composable (topSpace: Dp, backdrop: Modifier) -> Unit,
) {
    val liquid = rememberLiquidBackdrop()
    val density = LocalDensity.current
    var topSpace by remember { mutableStateOf(estimate) }
    Box(Modifier.fillMaxSize()) {
        content(topSpace, Modifier.glassBackdrop(liquid))
        if (fade > 0.dp) ScrollEdge(scrolled = true, height = fade)
        CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
            Column(
                Modifier.fillMaxWidth().onSizeChanged { topSpace = with(density) { it.height.toDp() } },
                content = top,
            )
        }
    }
}
