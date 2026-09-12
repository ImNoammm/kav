package uk.noammm.kav.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Moovit

/**
 * What a plan may contain. The transit modes go to the server as MVRouteTypes, so a
 * mode switched off is planned around rather than hidden; the taxi is a server flag
 * too. Bike, walking-only and share taxis are sorted out on the phone once the plan
 * is back, because the request has no word for them.
 */
enum class ResultFilter {
    BUS, TRAIN, LIGHT_RAIL, SHARE_TAXI, TAXI, BIKE, WALK;

    /** Recomputed on every access so a language change is picked up immediately. */
    val label: String
        get() = when (this) {
            BUS -> T("Bus", "אוטובוס")
            TRAIN -> T("Train", "רכבת")
            LIGHT_RAIL -> T("Light rail", "רכבת קלה")
            SHARE_TAXI -> T("Share taxi", "מונית שירות")
            TAXI -> T("Taxi", "מונית")
            BIKE -> T("Bike", "אופניים")
            WALK -> T("Walking only", "הליכה בלבד")
        }

    val desc: String
        get() = when (this) {
            BUS -> T("Every bus operator", "כל מפעילי האוטובוסים")
            TRAIN -> T("Israel Railways", "רכבת ישראל")
            LIGHT_RAIL -> T("Trams, the Carmelit and the cable cars", "רכבות קלות, הכרמלית והרכבלים")
            SHARE_TAXI -> T("Monit sherut lines", "קווי מוניות שירות")
            TAXI -> T("Gett rides, on their own or before a train", "נסיעות Gett, בפני עצמן או לפני רכבת")
            BIKE -> T("Cycling routes", "מסלולי אופניים")
            WALK -> T("Routes done entirely on foot", "מסלולים המתבצעים כולם ברגל")
        }
}

/** MVRouteType values for the request: every mode, minus the ones switched off. */
fun routeTypesFor(on: Set<ResultFilter>): List<Int> {
    val all = listOf(0, 1, 2, 3, 4, 5, 6, 7)
    val off = HashSet<Int>()
    if (ResultFilter.BUS !in on) off.add(3)
    if (ResultFilter.TRAIN !in on) off.add(2)
    if (ResultFilter.LIGHT_RAIL !in on) off.addAll(listOf(0, 1, 5, 6, 7))
    return all.filterNot { it in off }
}

/**
 * The plan's own results, minus what the rider does not want to see. A ride whose
 * line is not resolved yet is kept: its mode is unknown, and dropping it on a guess
 * would make cards vanish and come back as names arrive.
 */
fun filterResults(list: List<Moovit.Itinerary>, on: Set<ResultFilter>, r: Moovit.Resolved): List<Moovit.Itinerary> {
    if (on.size == ResultFilter.entries.size) return list
    return list.filter { it ->
        val legs = it.legs
        val hasRide = legs.any { l -> l.kind == Moovit.LegKind.RIDE }
        val hasTaxi = legs.any { l -> l.kind == Moovit.LegKind.TAXI }
        val hasBike = legs.any { l -> l.kind == Moovit.LegKind.BIKE }
        if (ResultFilter.TAXI !in on && hasTaxi) return@filter false
        if (ResultFilter.BIKE !in on && hasBike) return@filter false
        if (ResultFilter.WALK !in on && !hasRide && !hasTaxi && !hasBike) return@filter false
        legs.filter { l -> l.kind == Moovit.LegKind.RIDE }.all { ride ->
            val info = r.line(ride.lineId) ?: return@all true
            when (modeOf(r.routeType(info.agencyId))) {
                Mode.BUS -> ResultFilter.BUS in on
                Mode.TRAIN -> ResultFilter.TRAIN in on
                Mode.TAXI -> ResultFilter.SHARE_TAXI in on
                Mode.TRAM, Mode.SUBWAY, Mode.CABLE, Mode.GONDOLA, Mode.FUNICULAR -> ResultFilter.LIGHT_RAIL in on
                else -> true
            }
        }
    }
}

/** One row per filter, each with a switch. The same rows serve Settings and first launch. */
@Composable
fun FilterRows(enabled: Set<ResultFilter>, onToggle: (ResultFilter, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        ResultFilter.entries.forEach { f ->
            val on = f in enabled
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.plate)
                    .border(1.dp, if (on) K.borderStrong else K.border, RoundedCornerShape(14.dp))
                    .semantics { contentDescription = f.label; toggleableState = if (on) ToggleableState.On else ToggleableState.Off }
                    .clickable(role = Role.Switch) { onToggle(f, !on) }
                    .padding(horizontal = K.gap3, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                    when (f) {
                        ResultFilter.BUS -> ModeGlyph(Mode.BUS, if (on) K.text else K.dim, 18.dp)
                        ResultFilter.TRAIN -> ModeGlyph(Mode.TRAIN, if (on) K.text else K.dim, 18.dp)
                        ResultFilter.LIGHT_RAIL -> ModeGlyph(Mode.TRAM, if (on) K.text else K.dim, 18.dp)
                        ResultFilter.SHARE_TAXI -> ModeGlyph(Mode.TAXI, if (on) K.text else K.dim, 18.dp)
                        ResultFilter.TAXI -> ModeGlyph(Mode.TAXI, if (on) K.text else K.dim, 18.dp)
                        ResultFilter.BIKE -> BikeGlyph(if (on) K.text else K.dim)
                        ResultFilter.WALK -> WalkGlyph(if (on) K.text else K.dim, 16.dp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(f.label, fontSize = 14.sp, color = if (on) K.text else K.muted)
                    Text(f.desc, fontSize = 11.sp, color = K.dim, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Switch(on)
            }
        }
    }
}

/** A pill with a knob, in the accent when on and grey when off. */
@Composable
private fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) K.accent else K.surface4, label = "track")
    val knob by animateDpAsState(if (on) 20.dp else 2.dp, label = "knob")
    Box(Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(999.dp)).background(track)) {
        Box(
            Modifier.padding(start = knob, top = 2.dp).size(18.dp).clip(RoundedCornerShape(999.dp))
                .background(if (on) K.bg else K.muted),
        )
    }
}
