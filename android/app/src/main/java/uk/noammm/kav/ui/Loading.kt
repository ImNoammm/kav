package uk.noammm.kav.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * A vehicle running along a short line of stops, lighting each one as it passes.
 * The same mark stands in wherever the app is waiting on something, routes, the
 * timetable, a search, so a wait looks like the app rather than like a spinner.
 */
@Composable
fun LoadingPulse(label: String, modifier: Modifier = Modifier, wide: Boolean = false) {
    val t = rememberInfiniteTransition(label = "loading")
    val travel by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "travel",
    )
    val glow by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "glow",
    )
    Column(
        modifier.semantics { contentDescription = label },
        horizontalAlignment = if (wide) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Canvas(Modifier.width(if (wide) 120.dp else 88.dp).height(20.dp)) {
            val w = size.width; val h = size.height; val y = h / 2
            val inset = 6.dp.toPx()
            val stops = listOf(0f, .5f, 1f).map { inset + it * (w - inset * 2) }
            drawLine(K.surface4, Offset(stops.first(), y), Offset(stops.last(), y), 2.dp.toPx(), StrokeCap.Round)
            val x = stops.first() + travel * (stops.last() - stops.first())
            drawLine(K.accent, Offset(stops.first(), y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
            stops.forEachIndexed { i, sx ->
                val passed = x >= sx - 1f
                val near = (1f - abs(x - sx) / (w * .22f)).coerceIn(0f, 1f)
                drawCircle(K.bg, 4.5.dp.toPx(), Offset(sx, y))
                if (passed) drawCircle(K.accent.copy(alpha = .55f + .45f * near), 3.5.dp.toPx(), Offset(sx, y))
                else drawCircle(K.surface4, 3.dp.toPx(), Offset(sx, y), style = Stroke(1.5.dp.toPx()))
                if (i == stops.lastIndex && passed) drawCircle(K.accent.copy(alpha = .25f * (1f - glow)), 7.dp.toPx() * (1f + glow), Offset(sx, y))
            }
            drawCircle(K.bg, 6.dp.toPx(), Offset(x, y))
            drawCircle(K.accent, 4.dp.toPx(), Offset(x, y))
        }
        Spacer(Modifier.height(K.gap2))
        Text(label, fontSize = 14.sp, color = K.dim)
    }
}

/** The whole screen, waiting: for a route that will open on its own. */
@Composable
fun LoadingScreen(label: String, onBack: (() -> Unit)? = null) {
    androidx.activity.compose.BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) Row(Modifier.padding(K.gap3)) { BackButton(onBack) }
        Box(Modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current), contentAlignment = Alignment.Center) {
            LoadingPulse(label, wide = true)
        }
    }
}
