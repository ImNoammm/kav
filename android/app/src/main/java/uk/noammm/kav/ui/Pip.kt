package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import uk.noammm.kav.KavModel
import uk.noammm.kav.TripNotice
import uk.noammm.kav.plateIcon
import uk.noammm.kav.trackerIcon
import uk.noammm.kav.tripNotice
import kotlin.math.roundToInt

// The same card the trip notification shows: what to do now, and the trip as one bar.
@Composable
fun PipOverlay(model: KavModel) {
    val journey = model.activeJourney
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(15_000) }
    }
    Box(Modifier.fillMaxSize().background(K.bg), contentAlignment = Alignment.CenterStart) {
        val accent = K.accent.toArgb()
        val n = journey?.let { remember(it, model.journeyStep, model.fix, now, accent) {
            tripNotice(it, model.journeyStep, model.fix, now, accent)
        } }
        if (n == null) {
            Text(T("Trip ended", "הנסיעה הסתיימה"), style = K.type.body, color = K.dim, modifier = Modifier.padding(K.gap4))
            return@Box
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val plate = remember(n.glyph, n.tint) { plateIcon(n.glyph, n.tint).asImageBitmap() }
                Image(plate, contentDescription = null, modifier = Modifier.size(30.dp))
                // When the bus leaves your stop, or gets you to yours, in the search results' colours for that time.
                val due = n.due?.let { depColour(it) }
                Column(Modifier.weight(1f)) {
                    LiveText(
                        buildAnnotatedString {
                            append(n.title)
                            val at = when {
                                due == null || n.dueText.isEmpty() -> -1
                                n.title.startsWith(n.dueText) -> 0
                                else -> n.title.lastIndexOf(n.dueText)
                            }
                            if (at >= 0 && due != null) addStyle(SpanStyle(color = due), at, at + n.dueText.length)
                        },
                        style = K.type.subhead.copy(color = K.text, fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(n.text, n.arrive).filter { it.isNotBlank() }.joinToString(" · "),
                        style = K.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (n.chip.isNotBlank()) LiveText(
                    n.chip, style = K.type.caption, color = due ?: K.text, maxLines = 1,
                    modifier = Modifier.clip(K.capsule).background(K.plate).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            TripBar(n)
        }
    }
}

@Composable
private fun TripBar(n: TripNotice) {
    val tracker = remember(n.glyph, n.tint) { trackerIcon(n.glyph, n.tint).asImageBitmap() }
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        fun at(x: Float) = if (rtl) size.width - x else x
        val bar = 6.dp.toPx()
        val gap = 2.dp.toPx()
        val total = n.max.coerceAtLeast(1).toFloat()
        val y = size.height / 2
        var x = 0f
        for ((length, colour) in n.parts) {
            val w = length / total * size.width
            val a = at(x + gap / 2); val b = at(x + w - gap / 2)
            drawRoundRect(
                Color(colour), topLeft = Offset(minOf(a, b), y - bar / 2),
                size = Size(kotlin.math.abs(b - a).coerceAtLeast(bar), bar), cornerRadius = CornerRadius(bar / 2),
            )
            x += w
        }
        val t = size.height
        val cx = at((n.progress / total * size.width).coerceAtMost(size.width - t / 2).coerceAtLeast(t / 2))
        drawImage(
            tracker, dstOffset = IntOffset((cx - t / 2).roundToInt(), 0),
            dstSize = IntSize(t.roundToInt(), t.roundToInt()),
        )
    }
}
