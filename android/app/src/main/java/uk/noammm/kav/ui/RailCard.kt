package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.IsraelRail
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import java.util.Date

// The stations a route's trains take, as the railway numbers them: from the first train's to the last one's.
internal class RailPair(val from: Int, val to: Int, val fromName: String, val toName: String, val at: Long)

// For the first route that takes the train, the stations Israel Railways can be asked about.
internal fun railPair(trips: List<Moovit.Itinerary>, r: Moovit.Resolved): RailPair? {
    for (t in trips) {
        val trains = t.rides.filter(r::isTrain)
        if (trains.isEmpty()) continue
        val first = trains.first(); val last = trains.last()
        val from = r.stop(first.fromStop)?.code?.toIntOrNull()?.let(IsraelRail::stationOf) ?: continue
        val to = r.stop(last.toStop)?.code?.toIntOrNull()?.let(IsraelRail::stationOf) ?: continue
        if (from == to) continue
        return RailPair(from, to, r.stopName(first.fromStop).orEmpty(), r.stopName(last.toStop).orEmpty(), first.dep)
    }
    return null
}

private sealed class RailAnswer {
    object Asking : RailAnswer()
    object Failed : RailAnswer()
    class Found(val journeys: List<IsraelRail.Journey>) : RailAnswer()
}

// Israel Railways' own trains between those stations around the route's time, in a card beside Moovit's
// "View schedules" one, where the results talk about the train. It opens on a tap, and only then asks the railway: it
// learns the two stations and the day. Once asked, closed, it names the next train.
@Composable
// onJourney: a train route tapped, to open like any other route.
internal fun RailCard(net: Net?, pair: RailPair, modifier: Modifier = Modifier, onJourney: ((IsraelRail.Journey) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var answer by remember(pair.from, pair.to, pair.at) { mutableStateOf<RailAnswer?>(null) }
    var open by remember(pair.from, pair.to, pair.at) { mutableStateOf(false) }
    fun ask() {
        answer = RailAnswer.Asking
        scope.launch {
            answer = runCatching { withContext(Dispatchers.IO) { IsraelRail.search(pair.from, pair.to, pair.at * 1000) } }
                .fold({ RailAnswer.Found(it) }, { RailAnswer.Failed })
        }
    }
    val soon = (answer as? RailAnswer.Found)?.journeys?.filter { it.dep >= pair.at - 600 }?.take(5)
    val clock = clockFormat()
    Column(modifier.fillMaxWidth().panel(K.rCard).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) {
                    open = !open
                    if (open && (answer == null || answer == RailAnswer.Failed)) ask()
                }
                .padding(K.gap4),
            // The mark and the chevron stay at the top while the words under the title change and wrap.
            verticalAlignment = Alignment.Top,
        ) {
            RailMark(22.dp)
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(T("Israel Railways", "רכבת ישראל"), fontSize = 15.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                val next = soon?.firstOrNull()
                // Kav's own names for the two stations, as the stop names are set, whichever planner made the trip.
                val from = stationName(net, pair.from).ifBlank { pair.fromName }
                val to = stationName(net, pair.to).ifBlank { pair.toName }
                // Opened or closed, the line under the title fades from one wording to the other as the card's size eases.
                AnimatedContent(
                    if (!open && next != null) T("Next train ${clock.format(Date(next.dep * 1000))}",
                        "הרכבת הבאה ב־${clock.format(Date(next.dep * 1000))}")
                    else T("Trains from $from to $to", "רכבות מ$from אל $to"),
                    transitionSpec = {
                        (fadeIn(tween(220)) togetherWith fadeOut(tween(150)))
                            .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                    },
                    label = "railLine",
                ) { line -> Text(line, fontSize = 14.sp, color = K.muted) }
            }
            Spacer(Modifier.width(K.gap2))
            Chevron(open)
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(220, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(160)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, bottom = K.gap4),
                verticalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                when (val a = answer) {
                    null, RailAnswer.Asking -> Asking()
                    RailAnswer.Failed -> Chip(T("Couldn't reach Israel Railways · Try again", "לא הצלחנו להגיע לרכבת ישראל · נסו שוב"), false) { ask() }
                    is RailAnswer.Found -> {
                        if (soon.isNullOrEmpty()) Note(T("No more trains that day.", "אין עוד רכבות באותו יום."))
                        soon.orEmpty().forEachIndexed { i, j ->
                            Box(
                                Modifier.popIn(i, a).then(
                                    if (onJourney == null) Modifier
                                    else Modifier.clip(RoundedCornerShape(K.rTile)).clickable(role = Role.Button) { onJourney(j) },
                                ),
                            ) { RailJourney(net, j) }
                        }
                        Text(
                            T("From Israel Railways' own timetable", "מלוח הזמנים של רכבת ישראל"),
                            fontSize = 12.sp, color = K.dim,
                        )
                    }
                }
            }
        }
    }
}

// The railway being asked: a dot breathing beside the words.
@Composable
private fun Asking() {
    val pulse by rememberLivePulse()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = 1.6f - pulse }.clip(CircleShape).background(K.accent))
        }
        Spacer(Modifier.width(K.gap2))
        Text(T("Asking Israel Railways…", "שואלים את רכבת ישראל…"), fontSize = 14.sp, color = K.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RailJourney(net: Net?, j: IsraelRail.Journey) {
    val clock = clockFormat()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                T("${clock.format(Date(j.dep * 1000))} → ${clock.format(Date(j.arr * 1000))}",
                    "${clock.format(Date(j.dep * 1000))} ← ${clock.format(Date(j.arr * 1000))}"),
                style = Mono, fontSize = 17.sp, color = K.text, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(dur((j.arr - j.dep).toInt()), fontSize = 14.sp, color = K.muted)
        }
        val first = j.trains.first()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(when (val changes = j.trains.size - 1) {
                0 -> T("Direct", "ישירה")
                1 -> T("1 change", "החלפה אחת")
                else -> T("$changes changes", "$changes החלפות")
            })
            PlatformTag(first.fromPlatform)
            for (t in j.trains.drop(1)) {
                val at = stationName(net, t.from)
                if (at.isNotBlank()) Tag(T("Change at $at", "החלפה ב$at"))
                if (t.samePlatform) Tag(T("Same platform", "באותו רציף")) else PlatformTag(t.fromPlatform)
            }
            val late = j.trains.mapNotNull { it.late }.maxOrNull()
            if (late != null && late > 0) Tag(T("$late min late", "באיחור של $late דק׳"), late = true)
        }
    }
}

@Composable
private fun Tag(text: String, late: Boolean = false) {
    Box(Modifier.panel(6.dp).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, fontSize = 12.sp, color = if (late) K.critical else K.muted, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun Chevron(open: Boolean) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(260), label = "railChevron")
    Canvas(Modifier.size(14.dp).graphicsLayer { rotationZ = turn }) {
        val w = size.width
        drawLine(K.muted, Offset(w * .2f, w * .38f), Offset(w * .5f, w * .66f), w * .12f, StrokeCap.Round)
        drawLine(K.muted, Offset(w * .5f, w * .66f), Offset(w * .8f, w * .38f), w * .12f, StrokeCap.Round)
    }
}

// A station's name in Kav's timetable, by the railway's id for it.
private fun stationName(net: Net?, station: Int): String {
    val timetable = net ?: return ""
    val stop = IsraelRail.codeOf(station)?.let { KavLive.stopOfCode(timetable, it) }?.takeIf { it >= 0 } ?: return ""
    return timetable.stopName(stop)
}
