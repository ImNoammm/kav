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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shared screen transitions and tracked-vehicle pulse. */

private const val FAST = 240
private const val MED = 360

/** Pushing forward: the new screen comes in from the right, the old one steps back. */
fun forward(): ContentTransform =
    (slideInHorizontally(tween(MED)) { it / 6 } + fadeIn(tween(MED))) togetherWith
        fadeOut(tween(FAST))

/** Going back: the reverse, so the direction of travel is legible. */
fun backward(): ContentTransform =
    fadeIn(tween(MED)) togetherWith
        (slideOutHorizontally(tween(MED)) { it / 6 } + fadeOut(tween(FAST)))

/**
 * The language change, made watchable.
 *
 * Every word on screen reads [T.lang], and the layout direction is mirrored from it,
 * so turning it over repaints and re-mirrors the entire app between two frames. The
 * switch is made behind a short dip instead: the tree fades down, the language turns
 * over while there is nothing on it to read, and it comes back up in the other one,
 * drifting in from the side that language starts its reading on.
 *
 * A crossfade between two trees is the obvious way to do this and the wrong one: it
 * re-keys the content, so the screen you were on, the list you had scrolled and the
 * field you were typing in would all be rebuilt underneath the animation. Nothing is
 * re-keyed here - only the alpha and the offset of the tree already composed.
 */
@Composable
fun LanguageSwitch(content: @Composable () -> Unit) {
    val dip = remember { Animatable(1f) }
    LaunchedEffect(T.wanted) {
        if (T.wanted == null) {
            // a switch cancelled mid-dip still has to bring the tree back up
            if (dip.value < 1f) dip.animateTo(1f, tween(MED, easing = LinearOutSlowInEasing))
            return@LaunchedEffect
        }
        dip.animateTo(0f, tween(FAST / 2, easing = FastOutLinearInEasing))
        T.commit()
        dip.animateTo(1f, tween(MED, easing = LinearOutSlowInEasing))
    }
    // read after the commit as well as before it, so the old language leaves the way
    // it was read and the new one arrives the way it will be
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

/** Read the returned state inside drawing code so a pulse does not recompose the map. */
@Composable
fun rememberLivePulse(): State<Float> {
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(
        initialValue = 0.85f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale",
    )
}

/**
 * A pager as tall as the page on screen, not as tall as its tallest page.
 *
 * A pager measures every composed page against the same constraints and takes the
 * tallest, which left a dead strip beside a short card, drawn, hit-testable, and on
 * Home simply empty. Pages are still measured against the full [ceiling], so a short
 * page does not squash the ones either side of it, but the pager lays out, draws and
 * hit-tests only as tall as the page actually showing. The height follows the swipe
 * itself rather than switching when the page index flips, so a card sliding in slides
 * the height with it, which is also what an automatic step change animates through.
 * [heights] is fed by each page's own onSizeChanged; an unmeasured page holds the
 * pager's measured height, so a first visit is calm. The clip is outside the layout on
 * purpose: inside it, the pager clips to the full ceiling and the strip stays.
 */
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

/**
 * A card arriving: it rises a little and fades in, each one a beat after the last, so
 * a list of results lands rather than appears. Keyed on the list, so a fresh plan
 * plays it again and a re-sort does not.
 */
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
