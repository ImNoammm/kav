package uk.noammm.kav.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size

// Something on its way: a short accent capsule gliding along a quiet track, the way a vehicle moves along its line,
// eased at both ends, and the words saying what is loading.
@Composable
fun LoadingPulse(label: String, modifier: Modifier = Modifier, wide: Boolean = true, labelAbove: Boolean = false) {
    val travel by rememberInfiniteTransition(label = "loading").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "travel",
    )
    Column(
        modifier.semantics { contentDescription = label },
        horizontalAlignment = if (wide) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (labelAbove) {
            Text(label, style = K.type.subhead)
            Spacer(Modifier.height(K.gap3))
        }
        val track = K.fillStrong
        val ink = K.accentInk
        Canvas(Modifier.width(if (wide) 120.dp else 96.dp).height(4.dp)) {
            val h = size.height
            val run = size.width * .32f
            val x = mirrorX(travel) * (size.width - run)
            drawRoundRect(track, cornerRadius = CornerRadius(h / 2))
            drawRoundRect(ink, Offset(x, 0f), Size(run, h), CornerRadius(h / 2))
        }
        if (!labelAbove) {
            Spacer(Modifier.height(K.gap3))
            Text(label, style = K.type.subhead)
        }
    }
}

@Composable
fun LoadingBlock(label: String, modifier: Modifier = Modifier, labelAbove: Boolean = false) {
    Box(
        modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current),
        contentAlignment = Alignment.Center,
    ) { LoadingPulse(label, labelAbove = labelAbove) }
}

@Composable
fun LoadingScreen(label: String, onBack: (() -> Unit)? = null) {
    androidx.activity.compose.BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) Row(Modifier.padding(K.gap3)) { BackButton(onBack) }
        LoadingBlock(label)
    }
}
