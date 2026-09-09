package uk.noammm.kav.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
