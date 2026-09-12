package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.KavModel

/**
 * The small window: the step the rider is on, and nothing else. It reads the same
 * journey state as the full screen, so it moves on when the journey does.
 */
@Composable
fun PipOverlay(model: KavModel) {
    val journey = model.activeJourney
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(15_000) }
    }
    Box(Modifier.fillMaxSize().background(K.bg), contentAlignment = Alignment.CenterStart) {
        if (journey == null) {
            Text(T("Trip ended", "הנסיעה הסתיימה"), fontSize = 15.sp, color = K.dim, modifier = Modifier.padding(K.gap4))
            return@Box
        }
        val steps = remember(journey.trip, journey.fromLabel, journey.toLabel) {
            buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        }
        val index = model.journeyStep.coerceIn(0, steps.lastIndex)
        val (title, detail) = stepInstruction(steps[index], journey, index == steps.lastIndex - 1, now)
        Row(Modifier.fillMaxSize().padding(horizontal = K.gap3, vertical = K.gap2), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight(.7f).clip(RoundedCornerShape(999.dp)).background(K.accent))
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title, fontSize = 15.sp, lineHeight = 19.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(detail, fontSize = 12.sp, lineHeight = 15.sp, color = K.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
