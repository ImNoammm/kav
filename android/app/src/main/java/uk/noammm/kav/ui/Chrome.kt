package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Sig(plain: String, accent: String, modifier: Modifier = Modifier) {
    Text(
        "$plain $accent".trim(),
        style = Display, modifier = modifier,
    )
}

@Composable
private fun PlateButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .glassSurface(24.dp)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun BackButton(onClick: () -> Unit) = PlateButton(T("Back", "חזרה"), onClick) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        drawLine(K.muted, Offset(w * mirrorX(.66f), h * .16f), Offset(w * mirrorX(.30f), h * .50f), w * .11f, StrokeCap.Round)
        drawLine(K.muted, Offset(w * mirrorX(.30f), h * .50f), Offset(w * mirrorX(.66f), h * .84f), w * .11f, StrokeCap.Round)
    }
}

@Composable
fun ShareButton(onClick: () -> Unit) = PlateButton(T("Share trip", "שיתוף נסיעה"), onClick) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val a = Offset(w * .72f, h * .20f)
        val b = Offset(w * .26f, h * .50f)
        val c = Offset(w * .72f, h * .80f)
        drawLine(K.muted, b, a, w * .09f, StrokeCap.Round)
        drawLine(K.muted, b, c, w * .09f, StrokeCap.Round)
        drawCircle(K.muted, w * .13f, a)
        drawCircle(K.muted, w * .13f, b)
        drawCircle(K.muted, w * .13f, c)
    }
}

@Composable
fun SettingsButton(badge: Boolean = false, onClick: () -> Unit) = PlateButton(T("Settings", "הגדרות"), onClick) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val c = Offset(w * .5f, w * .5f)
        repeat(8) { i ->
            val a = i * PI.toFloat() / 4f
            val dx = cos(a); val dy = sin(a)
            drawLine(
                K.muted,
                Offset(c.x + dx * w * .28f, c.y + dy * w * .28f),
                Offset(c.x + dx * w * .40f, c.y + dy * w * .40f),
                w * .095f, StrokeCap.Round,
            )
        }
        drawCircle(K.muted, w * .27f, c, style = Stroke(w * .10f))
        drawCircle(K.muted, w * .115f, c, style = Stroke(w * .085f))
        if (badge) {
            drawCircle(K.bg, w * .26f, Offset(w * .92f, w * .08f))
            drawCircle(K.accent, w * .17f, Offset(w * .92f, w * .08f))
        }
    }
}

@Composable
fun Chip(text: String, lit: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .glassSurface(K.rControl)
            .then(if (lit) Modifier.background(K.plateStrong) else Modifier)
            .semantics { selected = lit }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, color = if (lit) K.text else K.muted)
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = K.dim) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = color, modifier = modifier)
}

@Composable
fun ScreenHeader(
    plain: String, accent: String, onSettings: (() -> Unit)? = null, back: (() -> Unit)? = null,
    badge: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap3).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        if (back != null) BackButton(back)
        Sig(plain, accent, Modifier.weight(1f))
        if (onSettings != null) SettingsButton(badge, onSettings)
    }
}
