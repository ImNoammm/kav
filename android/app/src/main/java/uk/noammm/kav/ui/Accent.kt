package uk.noammm.kav.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.text.font.FontWeight

val DefaultAccent = Color(0xFF9ABEFF)

// The slider's strength for a colour picked from white, about the presets'.
private const val SLIDER_SAT = 0.42f

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
)

private fun hsvOf(c: Color): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }

// Kav's colour: a slider along every hue, at the colour's own strength (the presets' when it's white), and the presets.
@Composable
fun AccentPicker(modifier: Modifier = Modifier, onChange: (Color) -> Unit) {
    val current = K.accent
    val hsv = remember(current) { hsvOf(current) }
    val strength = if (hsv[1] < .05f) SLIDER_SAT else hsv[1]
    val track = remember(strength) { (0..12).map { Color.hsv((it * 30 % 360).toFloat(), strength, 1f) } }
    // The dot's place on the slider: it follows a finger at once, and glides there when a preset is tapped.
    val target = hsv[0] / 360f
    val dot = remember { Animatable(target) }
    var glide by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (glide) { glide = false; dot.animateTo(target, tween(360, easing = FastOutSlowInEasing)) } else dot.snapTo(target)
    }

    fun pick(x: Float, width: Int) {
        val chosen = Color.hsv((x / width.coerceAtLeast(1)).coerceIn(0f, 1f) * 359.9f, strength, 1f)
        K.accent = chosen
        onChange(chosen)
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A tap or a drag along it picks; a drag up or down scrolls the page past it.
        Canvas(
            Modifier.fillMaxWidth().height(36.dp)
                .semantics { contentDescription = T("Colour", "צבע") }
                .pointerInput(strength) { detectTapGestures { pick(it.x, size.width) } }
                .pointerInput(strength) {
                    detectHorizontalDragGestures(onDragStart = { pick(it.x, size.width) }) { change, _ ->
                        change.consume(); pick(change.position.x, size.width)
                    }
                },
        ) {
            val bar = 14.dp.toPx()
            drawRoundRect(
                Brush.horizontalGradient(track), topLeft = Offset(0f, (size.height - bar) / 2),
                size = androidx.compose.ui.geometry.Size(size.width, bar),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2),
            )
            // On its way to a preset, the dot takes each colour it passes.
            val place = dot.value.coerceIn(0f, 1f)
            val at = Offset(place * size.width, size.height / 2)
            drawCircle(K.bg.copy(alpha = .55f), 15.dp.toPx(), at)
            drawCircle(if (dot.isRunning) Color.hsv(place * 359.9f, hsv[1], 1f) else current, 12.dp.toPx(), at)
            drawCircle(Color.White, 12.dp.toPx(), at, style = Stroke(2.5.dp.toPx()))
        }
        Spacer(Modifier.height(K.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap3), verticalAlignment = Alignment.CenterVertically) {
            AccentPresets.forEach { p ->
                val selected = hsvOf(p.color).let { it[1] < .02f && hsv[1] < .02f || (kotlin.math.abs(it[0] - hsv[0]) < 2f && kotlin.math.abs(it[1] - hsv[1]) < .02f) }
                Box(
                    Modifier.size(30.dp).clip(K.capsule).background(p.color)
                        .border(2.dp, if (selected) K.text else K.bg.copy(alpha = .6f), K.capsule)
                        .semantics { contentDescription = p.label }
                        .clickable(role = Role.Button) {
                            if (kotlin.math.abs(p.hue / 360f - target) > .001f) glide = true
                            K.accent = p.color; onChange(p.color)
                        },
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
                LinePlateBadge("69", Mode.BUS, operatorPlate(Operators.ofGtfs(5)))
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
