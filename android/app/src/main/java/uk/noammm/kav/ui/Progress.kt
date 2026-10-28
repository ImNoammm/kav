package uk.noammm.kav.ui

import android.content.Context
import uk.noammm.kav.data.Moovit
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Fix(val lat: Double, val lon: Double, val at: Long, val speed: Float = 0f)

private const val FRESH_S = 150L

internal fun Fix.isFresh(now: Long) = now - at <= FRESH_S
internal fun Fix.distanceTo(p: Pair<Double, Double>) = metres(lat, lon, p.first, p.second)
internal fun Fix.aboard(shape: List<Pair<Double, Double>>) =
    shape.size >= 2 && distanceToPath(lat, lon, shape) < 80 && alongPath(lat, lon, shape) >= 60

private class Flat(lat0: Double, lon0: Double) {
    private val kx = cos(Math.toRadians(lat0)) * 111_320.0
    private val ky = 110_574.0
    private val lat0 = lat0; private val lon0 = lon0
    fun x(lon: Double) = (lon - lon0) * kx
    fun y(lat: Double) = (lat - lat0) * ky
}

internal fun distanceToPath(lat: Double, lon: Double, path: List<Pair<Double, Double>>): Double {
    if (path.isEmpty()) return Double.MAX_VALUE
    if (path.size == 1) return metres(lat, lon, path[0].first, path[0].second)
    val f = Flat(lat, lon)
    var best = Double.MAX_VALUE
    for (i in 0 until path.lastIndex) {
        val ax = f.x(path[i].second); val ay = f.y(path[i].first)
        val bx = f.x(path[i + 1].second); val by = f.y(path[i + 1].first)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val u = t.coerceIn(0.0, 1.0)
        val px = ax + dx * u; val py = ay + dy * u
        best = min(best, sqrt(px * px + py * py))
    }
    return best
}

internal fun alongPath(lat: Double, lon: Double, path: List<Pair<Double, Double>>): Double {
    if (path.size < 2) return 0.0
    val f = Flat(lat, lon)
    var best = Double.MAX_VALUE; var bestAlong = 0.0; var walked = 0.0
    for (i in 0 until path.lastIndex) {
        val ax = f.x(path[i].second); val ay = f.y(path[i].first)
        val bx = f.x(path[i + 1].second); val by = f.y(path[i + 1].first)
        val dx = bx - ax; val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        val seg = metres(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
        val t = if (len == 0.0) 0.0 else (((-ax) * dx + (-ay) * dy) / (len * len)).coerceIn(0.0, 1.0)
        val px = ax + dx * t; val py = ay + dy * t
        val d = sqrt(px * px + py * py)
        if (d < best) { best = d; bestAlong = walked + seg * t }
        walked += seg
    }
    return bestAlong
}

internal fun bearingAlong(lat: Double, lon: Double, path: List<Pair<Double, Double>>, lookahead: Double = 40.0): Float? {
    if (path.size < 2) return null
    val at = alongPath(lat, lon, path)
    val from = pointAlong(path, at) ?: return null
    val to = pointAlong(path, at + lookahead) ?: path.last()
    val back = pointAlong(path, max(0.0, at - lookahead)) ?: path.first()
    val (a, b) = if (metres(from.first, from.second, to.first, to.second) > 3.0) from to to else back to from
    return bearing(a.first, a.second, b.first, b.second)
}

internal fun pointAlong(path: List<Pair<Double, Double>>, metresAlong: Double): Pair<Double, Double>? {
    if (path.isEmpty()) return null
    if (path.size == 1 || metresAlong <= 0.0) return path.first()
    var walked = 0.0
    for (i in 0 until path.lastIndex) {
        val seg = metres(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
        if (walked + seg >= metresAlong) {
            val t = if (seg == 0.0) 0.0 else (metresAlong - walked) / seg
            return (path[i].first + (path[i + 1].first - path[i].first) * t) to
                (path[i].second + (path[i + 1].second - path[i].second) * t)
        }
        walked += seg
    }
    return path.last()
}

internal fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Float {
    val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
    val dl = Math.toRadians(lo2 - lo1)
    val y = kotlin.math.sin(dl) * cos(p2)
    val x = cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
}

internal fun splitPath(path: List<Pair<Double, Double>>, lat: Double, lon: Double): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
    if (path.size < 2) return path to emptyList()
    val at = alongPath(lat, lon, path)
    val cut = pointAlong(path, at) ?: return emptyList<Pair<Double, Double>>() to path
    var walked = 0.0
    for (i in 0 until path.lastIndex) {
        val seg = metres(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
        if (walked + seg >= at) {
            return (path.subList(0, i + 1) + cut) to (listOf(cut) + path.subList(i + 1, path.size))
        }
        walked += seg
    }
    return path to emptyList()
}

private fun rideOf(step: Step.Wait, chosen: Map<Int, Int>) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
private fun rideOf(step: Step.Ride, chosen: Map<Int, Int>) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first

internal fun stepTarget(step: Step, r: Moovit.Resolved, chosen: Map<Int, Int>): Pair<Double, Double>? = when (step) {
    is Step.Start -> step.focus.firstOrNull()
    is Step.Walk -> r.stop(step.toStop)?.point ?: step.leg.shape.lastOrNull()
    is Step.Wait -> rideOf(step, chosen).let { r.stop(it.fromStop)?.point ?: it.shape.firstOrNull() }
    is Step.Ride -> rideOf(step, chosen).let { r.stop(it.toStop)?.point ?: it.shape.lastOrNull() }
    is Step.Taxi -> step.leg.taxiDropoff ?: step.leg.shape.lastOrNull()
    is Step.Cycle -> step.leg.shape.lastOrNull()
    is Step.Arrive -> step.focus.lastOrNull()
}

private fun departureOf(step: Step.Wait, r: Moovit.Resolved, chosen: Map<Int, Int>): Moovit.Departure {
    val ride = rideOf(step, chosen)
    return r.departures(ride, step.wait).firstOrNull { it.tripId == ride.tripId }
        ?: Moovit.Departure(ride.tripId, ride.dep)
}

// On a ride: on its route, `past` metres beyond where it is boarded. A phone moving at bus speed is on it. One reporting
// no speed, as in a bus stopped at a light or at a location set by hand, is too when it is off every walk of the
// trip, since only speed tells the bus from walking along its street.
private fun riding(fix: Fix, shape: List<Pair<Double, Double>>, onRoute: Double, past: Double, walks: List<List<Pair<Double, Double>>>) =
    shape.size >= 2 && distanceToPath(fix.lat, fix.lon, shape) < onRoute && alongPath(fix.lat, fix.lon, shape) > past &&
        (fix.speed > 5f || walks.none { distanceToPath(fix.lat, fix.lon, it) < ON_WALK_M })

private fun onStep(step: Step, r: Moovit.Resolved, chosen: Map<Int, Int>, fix: Fix, walks: List<List<Pair<Double, Double>>>): Boolean = when (step) {
    is Step.Walk -> {
        val start = step.leg.shape.firstOrNull()
        distanceToPath(fix.lat, fix.lon, step.leg.shape) < ON_WALK_M && (start == null || fix.distanceTo(start) > 100)
    }
    is Step.Wait -> stepTarget(step, r, chosen)?.let { fix.distanceTo(it) < 40 } == true
    is Step.Ride -> riding(fix, rideOf(step, chosen).shape, 50.0, 150.0, walks)
    is Step.Arrive -> stepTarget(step, r, chosen)?.let { fix.distanceTo(it) < 40 } == true
    else -> false
}

// A phone that stops reporting hasn't moved, so where you were last seen beats the timetable.
// `seen` is that place, or null once a ride has been assumed since; `waitForFix` holds the
// timetable back while a first fix is still to come.
private fun done(ctx: Context,
                 step: Step, r: Moovit.Resolved, chosen: Map<Int, Int>, now: Long, live: Fix?, seen: Fix?, waitForFix: Boolean,
                 walks: List<List<Pair<Double, Double>>>,
): Boolean {
    val target = stepTarget(step, r, chosen)
    return when (step) {
        is Step.Start -> uk.noammm.kav.Prefs.beginTripsImmediately(ctx) ||  (
            now >= step.time || (live != null && target != null && live.distanceTo(
                target
            ) > 60)
        )
        is Step.Walk -> when {
            target == null -> now >= step.leg.arr
            live != null -> live.distanceTo(target) < 40
            seen != null -> seen.distanceTo(target) < 40
            else -> !waitForFix && now >= step.leg.arr
        }
        is Step.Wait -> {
            val ride = rideOf(step, chosen)
            if (live != null && target != null) {
                val away = live.distanceTo(target)
                val onRoute = distanceToPath(live.lat, live.lon, ride.shape) < 60
                onRoute && away > 40 && live.speed > 5f || riding(live, ride.shape, 60.0, 150.0, walks)
            } else {
                val dep = departureOf(step, r, chosen)
                dep.status != 3 && now >= dep.timeUtc && when {
                    target == null -> true
                    seen != null -> seen.distanceTo(target) < 60
                    else -> !waitForFix
                }
            }
        }
        is Step.Ride -> {
            val ride = rideOf(step, chosen)
            (live != null && ride.shape.isNotEmpty() && live.distanceTo(ride.shape.last()) < 60) ||
                if (live != null && target != null) live.distanceTo(target) < 45 else now >= ride.arr + 60
        }
        is Step.Taxi -> if (live != null && target != null) live.distanceTo(target) < 45 else now >= step.leg.arr
        is Step.Cycle -> if (live != null && target != null) live.distanceTo(target) < 45 else now >= step.leg.arr
        is Step.Arrive -> false
    }
}

// Back to the walk to a ride's stop when the trip has moved on to waiting for or riding it, but the phone is on that
// walk again and well off the ride's route, as after a location that only passed the route.
private fun walkedBack(steps: List<Step>, i: Int, chosen: Map<Int, Int>, fix: Fix): Int? {
    val shape = when (val s = steps[i]) {
        is Step.Wait -> rideOf(s, chosen).shape
        is Step.Ride -> rideOf(s, chosen).shape
        else -> return null
    }
    if (shape.isEmpty() || distanceToPath(fix.lat, fix.lon, shape) < 80) return null
    val k = (i - 1 downTo 0).firstOrNull { steps[it] is Step.Walk || steps[it] is Step.Ride } ?: return null
    val walk = steps[k] as? Step.Walk ?: return null
    return k.takeIf { distanceToPath(fix.lat, fix.lon, walk.leg.shape) < ON_WALK_M }
}

private fun rideLeftBehind(step: Step.Ride, chosen: Map<Int, Int>, fix: Fix): Boolean {
    val shape = rideOf(step, chosen).shape
    if (shape.size < 2) return false
    return alongPath(fix.lat, fix.lon, shape) > pathLength(shape) - 60
}

// Minutes until the ride reaches the rider's stop: the time its last stop gets below.
internal fun minutesToAlight(ride: Moovit.Leg, r: Moovit.Resolved, fix: Fix?, now: Long): Long {
    val at = rideTimes(ride, r, riderTrip(ride, r, fix, now), emptyMap(), fix, now).lastOrNull()?.utc ?: r.alightAt(ride)
    return ceil((at - now) / 60.0).toLong().coerceAtLeast(0)
}

// When the rider's bus gets to a stop of the ride, and whether that's its live time.
internal class StopTime(val utc: Long, val live: Boolean)

private const val NEAR_BUS_M = 400.0
private const val ELSEWHERE_M = 1_500.0

// The bus the rider is on. Aboard, it's the one of the ride's line reported nearest to them; when that isn't near and
// the planned one is reported far off, they're on a bus with no live data (null). Otherwise it's the planned one.
internal fun riderTrip(ride: Moovit.Leg, r: Moovit.Resolved, fix: Fix?, now: Long): Long? {
    val aboard = fix?.takeIf { it.isFresh(now) && it.aboard(ride.shape) } ?: return ride.tripId
    val stops = ride.stops.toHashSet()
    val buses = r.live.values.filter { it.lineId == ride.lineId && it.hasLocation && it.stopId in stops }
        .groupBy { it.tripId }.mapValues { (_, at) -> at.maxBy { it.sampleUtc } }
    fun away(a: Moovit.Arrival) = metres(aboard.lat, aboard.lon, a.lat, a.lon)
    buses.values.minByOrNull(::away)?.takeIf { away(it) < NEAR_BUS_M }?.let { return it.tripId }
    return if (buses[ride.tripId]?.let { away(it) > ELSEWHERE_M } == true) null else ride.tripId
}

// Where the rider's bus was reported last, among its live times at the ride's stops.
internal fun busOn(ride: Moovit.Leg, r: Moovit.Resolved, trip: Long?): Moovit.Arrival? {
    if (trip == null) return null
    val stops = ride.stops.toHashSet()
    return r.live.values.filter { it.tripId == trip && it.hasLocation && it.stopId in stops }.maxByOrNull { it.sampleUtc }
}

// When the rider's bus reaches each stop of the ride: its live time where there is one. Elsewhere it's in step with the
// nearest things known around the stop (live times, the rider aboard right now), at the plan's pace past the last of
// them, so a jam that holds the bus up moves every stop after it. With nothing known, the plan moved by the ride's delay.
internal fun rideTimes(
    ride: Moovit.Leg, r: Moovit.Resolved, trip: Long?, names: Map<Int, Moovit.StopInfo>, fix: Fix?, now: Long,
): List<StopTime> {
    val n = ride.stops.size
    if (n == 0) return emptyList()
    val shape = ride.shape
    val length = if (shape.size >= 2) pathLength(shape) else 0.0
    // How far along the ride each stop is, 0 to 1: by the road where the stop's place is known, else by its count. Kept
    // in the ride's order, as two stops at one corner can land either way round on the road.
    var furthest = 0.0
    val share = ride.stops.mapIndexed { i, id ->
        val p = (names[id] ?: Moovit.knownStop(id) ?: r.stops[id])?.point
        val f = if (length > 0 && p != null) (alongPath(p.first, p.second, shape) / length).coerceIn(0.0, 1.0)
            else if (n > 1) i.toDouble() / (n - 1) else 1.0
        f.coerceAtLeast(furthest).also { furthest = it }
    }
    val span = (ride.arr - ride.dep).coerceAtLeast(0)
    fun planned(f: Double) = ride.dep + (f * span).toLong()
    val live = ride.stops.map { id -> trip?.let { r.live[Moovit.ArrivalKey(id, it)] }?.rtUtc?.takeIf { it > 0 } }
    val known = ArrayList<Pair<Double, Long>>()
    ride.stops.indices.forEach { i -> live[i]?.let { known += share[i] to it } }
    fix?.takeIf { length > 0 && it.isFresh(now) && it.aboard(shape) }
        ?.let { known += (alongPath(it.lat, it.lon, shape) / length) to now }
    val delay = r.delays[ride.tripId] ?: 0L
    return ride.stops.indices.map { i ->
        live[i]?.let { return@map StopTime(it, true) }
        val before = known.filter { it.first <= share[i] }.maxByOrNull { it.first }
        val after = known.filter { it.first >= share[i] }.minByOrNull { it.first }
        StopTime(
            when {
                before != null && after != null && after.first > before.first ->
                    before.second + ((share[i] - before.first) / (after.first - before.first) * (after.second - before.second)).toLong()
                before != null -> before.second + (planned(share[i]) - planned(before.first))
                after != null -> after.second - (planned(after.first) - planned(share[i]))
                else -> planned(share[i]) + delay
            },
            false,
        )
    }
}

internal fun pathLength(path: List<Pair<Double, Double>>): Double {
    var m = 0.0
    for (i in 0 until path.lastIndex) m += metres(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
    return m
}

internal fun journeyProgress(
    ctx: Context,
    steps: List<Step>,
    current: Int,
    r: Moovit.Resolved,
    chosen: Map<Int, Int>,
    now: Long,
    fix: Fix?,
    canLocate: Boolean,
): Int {
    if (steps.isEmpty()) return 0
    var i = current.coerceIn(0, steps.lastIndex)
    val live = fix?.takeIf { it.isFresh(now) }
    val walks = steps.mapNotNull { (it as? Step.Walk)?.leg?.shape?.takeIf { s -> s.isNotEmpty() } }
    if (live != null) walkedBack(steps, i, chosen, live)?.let { i = it }
    if (live != null) {
        for (k in steps.lastIndex downTo i + 1) {
            if (!onStep(steps[k], r, chosen, live, walks)) continue
            if ((i until k).any { j ->
                    steps[j] is Step.Ride && !done(ctx,steps[j], r, chosen, now, live, fix, false, walks) &&
                        !(j == i && rideLeftBehind(steps[j] as Step.Ride, chosen, live))
                }
            ) continue
            i = k
            break
        }
    }
    val waitForFix = fix == null && canLocate
    while (i < steps.lastIndex) {
        val seen = fix?.takeIf { f ->
            (0 until i).none { j -> (steps[j] as? Step.Ride)?.let { f.at < rideOf(it, chosen).arr - 300 } == true }
        }
        if (!done(ctx,steps[i], r, chosen, now, live, seen, waitForFix, walks)) break
        i++
    }
    return i
}

internal const val ON_WALK_M = 45.0

internal const val AT_WALK_START_M = 80.0

internal const val OFF_WALK_M = 120.0

internal fun onWalkNow(lat: Double, lon: Double, path: List<Pair<Double, Double>>, held: Boolean): Boolean {
    if (path.isEmpty()) return true
    val d = distanceToPath(lat, lon, path)
    if (held) return d <= OFF_WALK_M
    if (d <= ON_WALK_M) return true
    val s = path.first()
    return metres(lat, lon, s.first, s.second) <= AT_WALK_START_M
}

internal fun stopsProgress(
    ride: Moovit.Leg,
    stops: Map<Int, Moovit.StopInfo>,
    arrival: Moovit.Arrival?,
    fix: Fix?,
    now: Long,
): Float {
    val shape = ride.shape
    if (shape.size >= 2) {
        // The rider's bus, once it's under way on the ride's route (raw positions can sit off the road a little): past
        // the boarding stop as Moovit counts its stops, or, from Kav's server, which doesn't, by where it is on the road.
        val vehicle = arrival?.takeIf {
            it.hasLocation && distanceToPath(it.lat, it.lon, shape) < 150 &&
                (stopsPast(ride, it)?.let { n -> n > 0 } ?: (alongPath(it.lat, it.lon, shape) >= 60))
        }
        val live = fix?.takeIf { it.isFresh(now) && it.aboard(shape) }
        val at = when {
            live != null -> live.lat to live.lon
            vehicle != null -> vehicle.lat to vehicle.lon
            else -> null
        }
        if (at != null) {
            val along = alongPath(at.first, at.second, shape)
            var passed = 0
            var prev = 0.0
            var next = Double.NaN
            for (id in ride.stops) {
                val p = (stops[id] ?: Moovit.knownStop(id))?.point ?: continue
                val a = alongPath(p.first, p.second, shape)
                if (a <= along) { passed++; prev = a } else { next = a; break }
            }
            val frac = if (!next.isNaN() && next > prev) ((along - prev) / (next - prev)).toFloat().coerceIn(0f, 1f) else 0f
            return (passed + frac).coerceAtMost(ride.stops.size.toFloat())
        }
    }
    val past = arrival?.takeIf { it.hasLocation }?.let { stopsPast(ride, it) }
    if (past != null && past > 0) return past.coerceIn(0, ride.stops.size).toFloat()
    return -1f
}

// How many of the ride's stops the bus is past, as Moovit counts along the line: its next stop's place in the line
// against the boarding stop's, worked out from whichever stop of the ride the live time is for. Null when it doesn't say.
private fun stopsPast(ride: Moovit.Leg, a: Moovit.Arrival): Int? {
    val k = ride.stops.indexOf(a.stopId)
    if (k < 0 || a.stopIndex < 0 || a.nextStopIndex < 0) return null
    return a.nextStopIndex - (a.stopIndex - k)
}
