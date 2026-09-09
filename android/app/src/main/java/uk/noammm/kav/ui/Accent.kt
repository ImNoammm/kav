package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

val DefaultAccent = Color(0xFF9ABEFF)

/**
 * The rim of the wheel is as strong as the accent ever gets. The app is grey with one
 * colour on top of it, and that colour is text on the background and a fill under
 * dark text, past this it stops being legible in one of the two roles.
 */
private const val MAX_SAT = 0.62f

class AccentPreset(val label: String, val hue: Float, val sat: Float) {
    val color: Color get() = Color.hsv(hue, sat, 1f)
}

val AccentPresets = listOf(
    AccentPreset("Blue", 219f, .40f),
    AccentPreset("Green", 140f, .38f),
    AccentPreset("Teal", 178f, .42f),
    AccentPreset("Violet", 262f, .34f),
    AccentPreset("Amber", 44f, .48f),
    AccentPreset("Pink", 338f, .36f),
    AccentPreset("White", 0f, 0f),
)

private fun hsvOf(c: Color): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }

/**
 * A wheel to drag a dot around, hue by angle, strength by distance from the centre,
 * and a row of presets under it. The choice is applied to [K.accent] as the dot moves,
 * so whatever is on screen recolours with it; [onChange] is where to persist it.
 */
@Composable
fun AccentPicker(modifier: Modifier = Modifier, wheel: androidx.compose.ui.unit.Dp = 240.dp, onChange: (Color) -> Unit) {
    val current = K.accent
    val hsv = remember(current) { hsvOf(current) }
    val density = LocalDensity.current
    val rimColours = remember { (0..12).map { Color.hsv((it * 30 % 360).toFloat(), MAX_SAT, 1f) } }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(wheel).semantics { contentDescription = "Colour wheel" }) {
            val radiusPx = with(density) { wheel.toPx() / 2 }
            fun pick(at: Offset) {
                val dx = at.x - radiusPx; val dy = at.y - radiusPx
                val hue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
                val sat = (hypot(dx, dy) / (radiusPx * .92f)).coerceIn(0f, 1f) * MAX_SAT
                val chosen = Color.hsv(hue, sat, 1f)
                K.accent = chosen
                onChange(chosen)
            }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { pick(it) } }
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { pick(it) }) { change, _ -> change.consume(); pick(change.position) }
                    },
            ) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2 * .92f
                drawCircle(Brush.sweepGradient(rimColours, c), r, c)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), c, r), r, c)
                drawCircle(K.bg.copy(alpha = .35f), r, c, style = Stroke(1.dp.toPx()))
                // the dot: where the current colour sits on the wheel
                val angle = Math.toRadians(hsv[0].toDouble())
                val dist = (hsv[1] / MAX_SAT).coerceIn(0f, 1f) * r
                val dot = Offset(c.x + (cos(angle) * dist).toFloat(), c.y + (sin(angle) * dist).toFloat())
                drawCircle(K.bg.copy(alpha = .55f), 13.dp.toPx(), dot)
                drawCircle(current, 10.dp.toPx(), dot)
                drawCircle(Color.White, 10.dp.toPx(), dot, style = Stroke(2.5.dp.toPx()))
            }
        }
        Spacer(Modifier.height(K.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap3), verticalAlignment = Alignment.CenterVertically) {
            AccentPresets.forEach { p ->
                val selected = hsvOf(p.color).let { it[1] < .02f && hsv[1] < .02f || (kotlin.math.abs(it[0] - hsv[0]) < 2f && kotlin.math.abs(it[1] - hsv[1]) < .02f) }
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(999.dp)).background(p.color)
                        .border(2.dp, if (selected) K.text else K.bg.copy(alpha = .6f), RoundedCornerShape(999.dp))
                        .semantics { contentDescription = p.label }
                        .clickable(role = Role.Button) { K.accent = p.color; onChange(p.color) },
                )
            }
        }
    }
}

/**
 * A small Home screen, drawn with the app's own pieces so the colour lands on exactly
 * what it will land on: the search glyph, the resume link, the live step, Start.
 */
@Composable
fun AccentPreview(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.bg)
            .border(1.dp, K.border, RoundedCornerShape(K.rCard)).padding(K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Home", style = Display, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                drawCircle(K.muted, w * .18f, Offset(w * .5f, w * .5f), style = Stroke(w * .11f))
                drawCircle(K.muted, w * .44f, Offset(w * .5f, w * .5f), style = Stroke(w * .09f))
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.surface1)
                .border(0.5.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(16.dp))
                .padding(horizontal = K.gap3, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                drawCircle(K.accent, w * .29f, Offset(w * .40f, w * .40f), style = Stroke(w * .10f))
                drawLine(K.accent, Offset(w * .63f, w * .63f), Offset(w * .88f, w * .88f), w * .10f, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Text("Where to?", fontSize = 12.sp, color = K.muted)
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.surface1),
        ) {
            Row(
                Modifier.fillMaxWidth().background(K.accent.copy(alpha = .18f)).padding(horizontal = K.gap3, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Wait for", fontSize = 10.sp, color = K.accent, modifier = Modifier.weight(1f))
                Text("Resume", fontSize = 10.sp, color = K.accent)
            }
            Row(Modifier.padding(horizontal = K.gap3, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "472", fontSize = 11.sp, color = K.text,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(K.plate)
                        .border(1.dp, K.borderStrong, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(K.gap2))
                Text("to Tel Aviv", fontSize = 10.sp, color = K.muted, modifier = Modifier.weight(1f))
                Text("3 min", fontSize = 11.sp, color = K.accent)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(K.accent))
            Spacer(Modifier.width(6.dp))
            Text("Live", fontSize = 10.sp, color = K.accent, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(K.accent).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Start", fontSize = 10.sp, color = K.bg)
            }
        }
    }
}
