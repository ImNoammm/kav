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
import androidx.compose.ui.graphics.compositeOver
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

private fun liquidRim(floating: Boolean) =
    if (K.light) K.text.copy(alpha = if (floating) .16f else .1f)
    else Color.White

// OLED's white rims are thinner, so the black screen isn't boxed in.
private val rimWidth get() = if (K.look == Look.OLED) 0.6.dp else 1.dp

private val paneFill get() = K.surface1.copy(alpha = .62f)

@Composable
fun Modifier.glassSurface(radius: Dp = 22.dp): Modifier {
    if (!K.liquid) return panel(radius, solid = true)
    val shape = RoundedCornerShape(radius)
    val liquid = LocalLiquidBackdrop.current
    val pane = if (liquid != null) liquidPane(liquid, radius) else clip(shape).background(paneFill)
    return pane.border(rimWidth, liquidRim(floating = true), shape)
}

fun Modifier.panel(radius: Dp = K.rCard, solid: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius)
    val body = clip(shape).background(if (solid) paneFill.compositeOver(K.bg) else paneFill)
    return if (K.liquid) body.border(rimWidth, liquidRim(floating = solid), shape)
        else if (K.light) body.border(0.5.dp, K.text.copy(alpha = .14f), shape)
        else body.border(rimWidth, Color.White, shape)
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
