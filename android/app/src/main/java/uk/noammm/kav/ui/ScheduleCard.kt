package uk.noammm.kav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.Tab
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.lineDepartures

// What the card shows: the line's next times from the stop it's boarded at (seconds of the board's day, and whether
// each is live), and the stop and route its departures open at.
private class Schedule(val stop: Int, val route: Int, val times: List<Pair<Int, Boolean>>)

// "View schedules" above the routes: the first route's main line, its longest ride, and its next departures from the
// stop it's boarded at to the one it's left at, by Kav's timetable, live where Kav's server has them. Tapped, those
// departures: the line's at that stop, or a train's station board.
@Composable
internal fun ScheduleCard(model: KavModel, trip: Transit.Itinerary, r: Transit.Resolved, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ride = remember(trip) { trip.rides.maxByOrNull { it.arr - it.dep } } ?: return
    val from = r.stopName(ride.fromStop) ?: return
    val to = r.stopName(ride.toStop) ?: return
    val train = r.isTrain(ride)
    val number = ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }
    if (!train && number.isBlank()) return
    val schedule by produceState<Schedule?>(null, ride, r) {
        value = withContext(Dispatchers.IO) { runCatching { scheduleOf(ctx, model, ride, r, if (train) null else number) }.getOrNull() }
    }
    val s = schedule?.takeIf { it.times.isNotEmpty() } ?: return
    KavCard(modifier, onClick = {
        model.settingsOpen = false
        if (train) { model.stationStop = s.stop; model.tab = Tab.Stations }
        else { model.lineFocusStop = s.stop; model.lineRoute = s.route; model.tab = Tab.Lines }
    }) {
        Text(T("View schedules", "לוחות זמנים"), style = K.type.headline)
        Text(
            scheduleTitle(train, number, from, to),
            style = K.type.subhead,
        )
        Row(Modifier.padding(top = K.gap2), horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            for ((at, live) in s.times) Text(hhmm(at), style = K.type.body, color = if (live) K.realtime else K.text)
        }
    }
}

// The line and the stretch a card is about. In Hebrew "מ־" joins the name as it does wherever Kav names a start, since the
// name may be in English.
internal fun scheduleTitle(train: Boolean, number: String, from: String, to: String): String =
    if (train) T("Trains from $from to $to", "רכבות מ־$from אל $to") else T("Line $number from $from to $to", "קו $number מ־$from אל $to")

private suspend fun scheduleOf(ctx: android.content.Context, model: KavModel, ride: Transit.Leg, r: Transit.Resolved, number: String?): Schedule? {
    val net = model.net ?: uk.noammm.kav.loadNet(ctx)
    val fromCode = r.stop(ride.fromStop)?.code?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val toCode = r.stop(ride.toStop)?.code?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val from = net.code.indices.filter { net.code[it] == fromCode }.toSet()
    val to = net.code.indices.filter { net.code[it] == toCode }.toSet()
    // From a little before the ride, as the timetable can have its bus a few minutes from the plan's time, but not
    // before now on the ride's own day.
    val now = System.currentTimeMillis() / 1000
    val day = KavLive.dayOf(ride.dep)
    val since = maxOf(KavLive.secondOfDay(ride.dep) - 300, if (KavLive.dayOf(now) == day) KavLive.secondOfDay(now) - 60 else 0)
    val deps = net.lineDepartures(from, to, number, since, day)
    val first = deps.firstOrNull() ?: return null
    // Live times only for a ride leaving within the server's look ahead today: a later one's are the timetable's.
    val live = if (KavLive.stopsEnabled && ride.dep - now in -600..Transit.LIVE_AHEAD_S && day == KavLive.dayOf(now)) {
        val board = KavLive.stops(listOf(fromCode))
        from.flatMap { KavLive.boardTimes(net, it, board, now) }.filter { it.trip >= 0 }.associate { it.trip to it.rt }
    } else emptyMap()
    return Schedule(
        net.stStop[first.first], net.tripRoute[net.tripOf(first.first)],
        deps.map { (st, dep) -> live[net.tripOf(st)]?.let { it to true } ?: (dep to false) },
    )
}
