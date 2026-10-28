package uk.noammm.kav.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// A stop's actions in one panel, each a glyph over its name like the tab bar's, so the board's buttons look like the
// rest of Kav instead of a row of tags.
@Composable
internal fun ActionStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().panel(K.rCard).padding(4.dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// A thin rule between two actions.
@Composable
internal fun ActionDivider() {
    Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(K.plateStrong))
}

// lit: on, for a stop that is saved or has alerts. badge: a count on the glyph's corner.
@Composable
internal fun RowScope.ActionTile(
    label: String,
    description: String,
    lit: Boolean = false,
    badge: Int = 0,
    onClick: () -> Unit,
    glyph: DrawScope.(ink: Color, fill: Float) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) .9f else 1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium), label = "press",
    )
    // Turning on gives the glyph a small pop; turning off just settles.
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(lit) }
    LaunchedEffect(lit) {
        if (lit && !was) {
            pop.animateTo(1.28f, tween(110))
            pop.animateTo(1f, spring(dampingRatio = .38f, stiffness = 520f))
        }
        was = lit
    }
    val fill by animateFloatAsState(if (lit) 1f else 0f, tween(220), label = "fill")
    val shown by animateFloatAsState(if (badge > 0) 1f else 0f, spring(dampingRatio = .5f, stiffness = 600f), label = "badge")
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(K.rCard - 4.dp))
            .semantics(mergeDescendants = true) { contentDescription = description; selected = lit }
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 10.dp)
            .graphicsLayer { scaleX = press; scaleY = press },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
                glyph(if (lit) K.text else K.muted, fill)
            }
            if (shown > 0f) Box(
                Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp)
                    .graphicsLayer { scaleX = shown; scaleY = shown; alpha = shown.coerceIn(0f, 1f) }
                    .clip(K.capsule).background(K.bg).padding(1.5.dp)
                    .clip(K.capsule).background(K.accent)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${badge.coerceAtLeast(1)}", style = K.type.caption, color = K.onAccent, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label, fontSize = 12.sp, lineHeight = 14.sp, color = if (lit) K.text else K.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DrawScope.sw() = size.width * .085f

// The Stations tab's stop, a ring on a post, small, at x.
private fun DrawScope.stopPin(x: Float, ink: Color) {
    val w = size.width; val h = size.height
    drawCircle(ink, w * .13f, Offset(w * x, h * .32f), style = Stroke(sw()))
    drawLine(ink, Offset(w * x, h * .45f), Offset(w * x, h * .84f), sw(), StrokeCap.Round)
}

private fun DrawScope.arrow(x1: Float, x2: Float, y: Float, ink: Color) {
    val w = size.width; val h = size.height
    drawLine(ink, Offset(w * mirrorX(x1), h * y), Offset(w * mirrorX(x2), h * y), sw(), StrokeCap.Round)
    val back = if (x2 > x1) -.15f else .15f
    drawLine(ink, Offset(w * mirrorX(x2), h * y), Offset(w * mirrorX(x2 + back), h * (y - .14f)), sw(), StrokeCap.Round)
    drawLine(ink, Offset(w * mirrorX(x2), h * y), Offset(w * mirrorX(x2 + back), h * (y + .14f)), sw(), StrokeCap.Round)
}

// Plan from this stop: the stop, and the way on from it.
internal fun DrawScope.fromHereGlyph(ink: Color) {
    stopPin(mirrorX(.24f), ink)
    arrow(.46f, .94f, .58f, ink)
}

// Plan to this stop: the way in, and the stop.
internal fun DrawScope.toHereGlyph(ink: Color) {
    arrow(.06f, .54f, .58f, ink)
    stopPin(mirrorX(.76f), ink)
}

// fill: how far the accent has filled it, for one that is on.
internal fun DrawScope.starGlyph(ink: Color, fill: Float) {
    val w = size.width; val h = size.height
    val star = Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) .45f else .2f
            val a = -PI / 2 + i * PI / 5
            val x = w * (.5f + r * cos(a).toFloat()); val y = h * (.54f + r * sin(a).toFloat())
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    if (fill > 0f) drawPath(star, K.accent.copy(alpha = fill))
    drawPath(star, ink, style = Stroke(sw(), join = StrokeJoin.Round))
}

internal fun DrawScope.bellGlyph(ink: Color, fill: Float) {
    val w = size.width; val h = size.height
    val bell = Path().apply {
        moveTo(w * .16f, h * .74f)
        lineTo(w * .84f, h * .74f)
        lineTo(w * .76f, h * .62f)
        lineTo(w * .76f, h * .44f)
        cubicTo(w * .76f, h * .26f, w * .65f, h * .17f, w * .5f, h * .17f)
        cubicTo(w * .35f, h * .17f, w * .24f, h * .26f, w * .24f, h * .44f)
        lineTo(w * .24f, h * .62f)
        close()
    }
    if (fill > 0f) drawPath(bell, K.accent.copy(alpha = fill))
    drawPath(bell, ink, style = Stroke(sw(), join = StrokeJoin.Round))
    drawLine(ink, Offset(w * .5f, h * .08f), Offset(w * .5f, h * .17f), sw(), StrokeCap.Round)
    drawArc(ink, 20f, 140f, false, Offset(w * .4f, h * .7f), Size(w * .2f, h * .18f), style = Stroke(sw(), cap = StrokeCap.Round))
}

// Street View's figure, standing on the circle it turns around.
internal fun DrawScope.streetGlyph(ink: Color) {
    val w = size.width; val h = size.height
    drawOval(ink, Offset(w * .1f, h * .66f), Size(w * .8f, h * .24f), style = Stroke(sw() * .85f))
    drawCircle(ink, w * .1f, Offset(w * .5f, h * .17f))
    val body = Path().apply {
        moveTo(w * .5f, h * .33f); lineTo(w * .5f, h * .6f)
        moveTo(w * .33f, h * .45f); lineTo(w * .5f, h * .36f); lineTo(w * .67f, h * .45f)
        moveTo(w * .5f, h * .6f); lineTo(w * .41f, h * .79f)
        moveTo(w * .5f, h * .6f); lineTo(w * .59f, h * .79f)
    }
    drawPath(body, ink, style = Stroke(sw(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
