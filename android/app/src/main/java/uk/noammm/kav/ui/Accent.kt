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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.text.font.FontWeight

val DefaultAccent = Color(0xFF9ABEFF)

private const val MAX_SAT = 0.62f

class AccentPreset(private val nameEn: String, private val nameHe: String, val hue: Float, val sat: Float) {
    val label: String get() = T(nameEn, nameHe)
    val color: Color get() = Color.hsv(hue, sat, 1f)
}

val AccentPresets = listOf(
    AccentPreset("Blue", "כחול", 219f, .40f),
    AccentPreset("Green", "ירוק", 140f, .38f),
    AccentPreset("Teal", "טורקיז", 178f, .42f),
    AccentPreset("Violet", "סגול", 262f, .34f),
    AccentPreset("Amber", "ענבר", 44f, .48f),
    AccentPreset("Pink", "ורוד", 338f, .36f),
    AccentPreset("White", "לבן", 0f, 0f),
)

private fun hsvOf(c: Color): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }

@Composable
fun AccentPicker(modifier: Modifier = Modifier, wheel: androidx.compose.ui.unit.Dp = 240.dp, onChange: (Color) -> Unit) {
    val current = K.accent
    val hsv = remember(current) { hsvOf(current) }
    val density = LocalDensity.current
    val rimColours = remember { (0..12).map { Color.hsv((it * 30 % 360).toFloat(), MAX_SAT, 1f) } }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(wheel).semantics { contentDescription = T("Colour wheel", "גלגל צבעים") }) {
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
                    Modifier.size(30.dp).clip(K.capsule).background(p.color)
                        .border(2.dp, if (selected) K.text else K.bg.copy(alpha = .6f), K.capsule)
                        .semantics { contentDescription = p.label }
                        .clickable(role = Role.Button) { K.accent = p.color; onChange(p.color) },
                )
            }
        }
    }
}

@Composable
fun AccentPreview(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.bg)
            .border(0.5.dp, K.separator, RoundedCornerShape(K.rCard)).padding(K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Row(
                Modifier.weight(1f).height(32.dp).clip(K.capsule).background(K.fill).padding(horizontal = K.gap3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(14.dp))
                Text(T("Where to?", "לאן?"), fontSize = 12.sp, color = K.textTertiary)
            }
            Box(Modifier.size(32.dp).clip(K.capsule).background(K.fill), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.fillMaxWidth().panel(K.rTile).padding(K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T("Current trip", "הנסיעה הנוכחית"), fontSize = 12.sp, color = K.textSecondary, modifier = Modifier.weight(1f))
                Text(T("Resume", "המשך"), fontSize = 12.sp, color = K.accentInk, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Text(
                    "472", fontSize = 12.sp, color = K.text, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(K.fill).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Text(T("to Tel Aviv", "לתל אביב"), fontSize = 12.sp, color = K.text, modifier = Modifier.weight(1f))
                LiveGlyph(K.realtime, 10.dp)
                Text(T("3 min", "3 דק׳"), fontSize = 12.sp, color = K.realtime, fontWeight = FontWeight.Medium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Text(
                T("Bus", "אוטובוס"), fontSize = 12.sp, color = K.onAccent, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(K.capsule).background(K.accent).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(
                T("Train", "רכבת"), fontSize = 12.sp, color = K.textSecondary,
                modifier = Modifier.clip(K.capsule).background(K.fill).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(30.dp, 18.dp).clip(K.capsule).background(K.accent)) {
                Box(Modifier.align(Alignment.CenterEnd).padding(2.dp).size(14.dp).clip(K.capsule).background(Color.White))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(34.dp).clip(K.capsule).background(K.accent),
            contentAlignment = Alignment.Center,
        ) { Text(T("Start", "התחלה"), fontSize = 12.sp, color = K.onAccent, fontWeight = FontWeight.Medium) }
    }
}
