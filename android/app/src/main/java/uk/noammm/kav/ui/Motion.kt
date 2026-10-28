package uk.noammm.kav.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring

private const val FAST = 240
private const val MED = 360

fun forward(): ContentTransform =
    (slideInHorizontally(tween(MED)) { it / 6 } + fadeIn(tween(MED))) togetherWith
        fadeOut(tween(FAST))

fun backward(): ContentTransform =
    fadeIn(tween(MED)) togetherWith
        (slideOutHorizontally(tween(MED)) { it / 6 } + fadeOut(tween(FAST)))

private val lift = spring<IntOffset>(dampingRatio = .9f, stiffness = 380f)

// No size animation: the default one grows search out of the middle of the screen.
fun searchIn(rise: Int) = ContentTransform(
    slideInVertically(lift) { rise } + fadeIn(tween(220, delayMillis = 30, easing = LinearOutSlowInEasing)),
    fadeOut(tween(FAST)), sizeTransform = null,
)

fun searchOut(rise: Int) = ContentTransform(
    fadeIn(tween(FAST)),
    slideOutVertically(tween(FAST, easing = FastOutLinearInEasing)) { rise } + fadeOut(tween(160)), sizeTransform = null,
)

@Composable
fun underSearch(open: Boolean): State<Float> = animateFloatAsState(
    if (open) 0f else 1f,
    if (open) tween(120, easing = LinearOutSlowInEasing) else tween(200, delayMillis = 80, easing = LinearOutSlowInEasing),
    label = "underSearch",
)

@Composable
fun LanguageSwitch(content: @Composable () -> Unit) {
    val dip = remember { Animatable(1f) }
    LaunchedEffect(T.wanted) {
        if (T.wanted == null) {
            if (dip.value < 1f) dip.animateTo(1f, tween(MED, easing = LinearOutSlowInEasing))
            return@LaunchedEffect
        }
        dip.animateTo(0f, tween(FAST / 2, easing = FastOutLinearInEasing))
        T.commit()
        dip.animateTo(1f, tween(MED, easing = LinearOutSlowInEasing))
    }
    val drift = if (T.rtl) -1f else 1f
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val p = dip.value
            alpha = p
            scaleX = 0.985f + 0.015f * p
            scaleY = 0.985f + 0.015f * p
            translationX = (1f - p) * 14.dp.toPx() * drift
        },
    ) { content() }
}

@Composable
fun rememberLivePulse(): State<Float> {
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(
        initialValue = 0.85f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )
}

fun Modifier.pageSized(pager: PagerState, heights: Map<Int, Int>, ceiling: Int, bottom: Boolean): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val page = measurable.measure(constraints.copy(minHeight = 0, maxHeight = ceiling))
        val from = pager.currentPage
        val slide = pager.currentPageOffsetFraction
        val to = if (slide > 0f) from + 1 else from - 1
        val here = heights[from] ?: page.height
        val next = heights[to] ?: here
        val h = (here + (next - here) * abs(slide)).roundToInt().coerceIn(0, page.height)
        layout(page.width, h) { page.place(0, if (bottom) h - page.height else 0) }
    }

@Composable
fun Modifier.popIn(index: Int, key: Any?): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(minOf(index, 7) * 45L)
        progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 420f))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 22.dp.toPx()
        scaleX = 0.97f + 0.03f * p
        scaleY = 0.97f + 0.03f * p
    }
}

// A screen over the tabs (Settings, paying, the payment history, a report) slides up whole and opaque and back down,
// so the screen under it never shows through a fade.
fun modalIn(): EnterTransition = slideInVertically(spring(dampingRatio = 1f, stiffness = 400f)) { it }
fun modalOut(): ExitTransition = slideOutVertically(spring(dampingRatio = 1f, stiffness = 400f)) { it }
