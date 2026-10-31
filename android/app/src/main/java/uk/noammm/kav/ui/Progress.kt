package uk.noammm.kav.ui

import uk.noammm.kav.data.Transit
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// accuracy: how far off the position may be, in metres, as the phone says.
data class Fix(val lat: Double, val lon: Double, val at: Long, val speed: Float = 0f, val accuracy: Float = POOR_FIX_M)

private const val FRESH_S = 150L

// A fix that doesn't say how accurate it is counts as this rough, as rough as any the trip uses.
internal const val POOR_FIX_M = 200f

// How a trip goes by a rough fix. Its distances below allow for a fix up to SHARP_FIX_M off; a rougher one has slack,
// the rest of its accuracy. A step ends when its end is within one slack more. Anything else that moves the trip on,
// starting a ride above all, has to hold with three slacks to spare, so a rough fix jumping round a stop or a walk never
// starts a ride. Only the ride's route itself gets just one.
private const val SHARP_FIX_M = 20f

private val Fix.slack: Double get() = (accuracy.coerceIn(0f, POOR_FIX_M) - SHARP_FIX_M).coerceAtLeast(0f).toDouble()

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

// How near a trip's way the rider has to be for the map's button to take the map to them. Further, as with a trip
// between places far from where they are, it frames the trip's step again instead.
internal const val NEAR_TRIP_M = 1_000.0

// Where the map's button centres the map: on the rider near the trip, else nowhere of theirs, so the map frames the step.
// A trip with no way drawn keeps the rider.
internal fun recentreOn(here: Pair<Double, Double>?, paths: List<List<Pair<Double, Double>>>): Pair<Double, Double>? {
    val at = here ?: return null
    if (paths.all { it.isEmpty() }) return at
    return at.takeIf { paths.any { distanceToPath(at.first, at.second, it) <= NEAR_TRIP_M } }
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

// How far along a road a place is: where the road passes nearest it, within reach metres, at least from metres along
// (less 5, for a report that went back a little). A road that comes back on itself, a U-turn, is met at both of its
// sides; from says which side comes next on the way. Null where the road doesn't pass that close, or where a stretch
// already passed is clearly nearer: the vehicle went back, not round.
internal fun alongFrom(at: Pair<Double, Double>, path: List<Pair<Double, Double>>, from: Double = 0.0, reach: Double = 40.0): Double? {
    if (path.size < 2) return null
    val f = Flat(at.first, at.second)
    var nearest = Double.MAX_VALUE
    var best = Double.MAX_VALUE; var found: Double? = null; var walked = 0.0
    for (i in 0 until path.lastIndex) {
        val ax = f.x(path[i].second); val ay = f.y(path[i].first)
        val bx = f.x(path[i + 1].second); val by = f.y(path[i + 1].first)
        val dx = bx - ax; val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        val seg = metres(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
        val t = if (len == 0.0) 0.0 else (((-ax) * dx + (-ay) * dy) / (len * len)).coerceIn(0.0, 1.0)
        val d = sqrt((ax + dx * t).let { it * it } + (ay + dy * t).let { it * it })
        val along = walked + seg * t
        nearest = min(nearest, d)
        if (d <= reach && along >= from - 5.0 && d < best) { best = d; found = along }
        walked += seg
    }
    return found?.takeIf { best <= nearest + 10.0 }
}

// The stretch of a road between two places on it, the second ahead of the first the way the road goes, to glide a
// vehicle along. Null when either is off the road, the second is behind, or the way round is far longer than the gap,
// the road meeting the place again further on.
internal fun roadBetween(
    from: Pair<Double, Double>, to: Pair<Double, Double>, path: List<Pair<Double, Double>>,
): List<Pair<Double, Double>>? {
    val a = alongFrom(from, path) ?: return null
    val b = alongFrom(to, path, a) ?: return null
    if (b <= a || b - a > 3 * metres(from.first, from.second, to.first, to.second) + 300) return null
    val out = arrayListOf(pointAlong(path, a) ?: return null)
    var walked = 0.0
    for (i in 0 until path.lastIndex) {
        walked += metres(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
        if (walked > a && walked < b) out += path[i + 1]
    }
    out += pointAlong(path, b) ?: return null
    return out
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

internal fun stepTarget(step: Step, r: Transit.Resolved, chosen: Map<Int, Int>): Pair<Double, Double>? = when (step) {
    is Step.Start -> step.focus.firstOrNull()
    is Step.Walk -> r.stop(step.toStop)?.point ?: step.leg.shape.lastOrNull()
    is Step.Wait -> rideOf(step, chosen).let { r.stop(it.fromStop)?.point ?: it.shape.firstOrNull() }
    is Step.Ride -> rideOf(step, chosen).let { r.stop(it.toStop)?.point ?: it.shape.lastOrNull() }
    is Step.Taxi -> step.leg.taxiDropoff ?: step.leg.shape.lastOrNull()
    is Step.Cycle -> step.leg.shape.lastOrNull()
    is Step.Arrive -> step.focus.lastOrNull()
}

private fun departureOf(step: Step.Wait, r: Transit.Resolved, chosen: Map<Int, Int>): Transit.Departure {
    val ride = rideOf(step, chosen)
    return r.departures(ride, step.wait).firstOrNull { it.tripId == ride.tripId }
        ?: Transit.Departure(ride.tripId, ride.dep)
}

// On a ride: on its route, `past` metres beyond where it is boarded. A phone moving at bus speed is on it. One reporting
// no speed, as in a bus stopped at a light or at a location set by hand, is too when it is off every walk of the
// trip, since only speed tells the bus from walking along its street.
private fun riding(fix: Fix, shape: List<Pair<Double, Double>>, onRoute: Double, past: Double, walks: List<List<Pair<Double, Double>>>) =
    shape.size >= 2 && distanceToPath(fix.lat, fix.lon, shape) < onRoute + fix.slack &&
        alongPath(fix.lat, fix.lon, shape) > past + 3 * fix.slack && fix.distanceTo(shape.first()) >= 3 * fix.slack && offWalks(fix, walks)

// Off every walk of the trip: by a walk's width, or at bus speed by nothing more than a rough fix's three slacks.
private fun offWalks(fix: Fix, walks: List<List<Pair<Double, Double>>>): Boolean {
    val clear = (if (fix.speed > 5f) 0.0 else ON_WALK_M) + 3 * fix.slack
    return walks.none { distanceToPath(fix.lat, fix.lon, it) < clear }
}

private fun onStep(step: Step, r: Transit.Resolved, chosen: Map<Int, Int>, fix: Fix, walks: List<List<Pair<Double, Double>>>): Boolean = when (step) {
    is Step.Walk -> {
        val start = step.leg.shape.firstOrNull()
        distanceToPath(fix.lat, fix.lon, step.leg.shape) < ON_WALK_M - 3 * fix.slack &&
            (start == null || fix.distanceTo(start) > 100 + 3 * fix.slack)
    }
    is Step.Wait -> stepTarget(step, r, chosen)?.let { fix.distanceTo(it) < 40 - 3 * fix.slack } == true
    is Step.Ride -> riding(fix, rideOf(step, chosen).shape, 50.0, 150.0, walks)
    is Step.Arrive -> stepTarget(step, r, chosen)?.let { fix.distanceTo(it) < 40 - 3 * fix.slack } == true
    else -> false
}

// A phone that stops reporting hasn't moved, so where you were last seen beats the timetable.
// `seen` is that place, or null once a ride has been assumed since; `waitForFix` holds the
// timetable back while a first fix is still to come.
// startNow: the rider wants a trip to move to its first walk at once (Settings), not at its time or once they move off.
private fun done(
    step: Step, r: Transit.Resolved, chosen: Map<Int, Int>, now: Long, live: Fix?, seen: Fix?, waitForFix: Boolean,
    walks: List<List<Pair<Double, Double>>>, startNow: Boolean = false,
): Boolean {
    val target = stepTarget(step, r, chosen)
    val slack = live?.slack ?: 0.0
    return when (step) {
        is Step.Start -> startNow || now >= step.time || (live != null && target != null && live.distanceTo(target) > 60 + 3 * slack)
        is Step.Walk -> when {
            target == null -> now >= step.leg.arr
            live != null -> live.distanceTo(target) < 40 + slack
            seen != null -> seen.distanceTo(target) < 40 + seen.slack
            else -> !waitForFix && now >= step.leg.arr
        }
        is Step.Wait -> {
            val ride = rideOf(step, chosen)
            if (live != null && target != null) {
                val away = live.distanceTo(target)
                val onRoute = distanceToPath(live.lat, live.lon, ride.shape) < 60 + slack
                onRoute && away > 40 + 3 * slack && live.speed > 5f && offWalks(live, walks) ||
                    riding(live, ride.shape, 60.0, 150.0, walks)
            } else {
                val dep = departureOf(step, r, chosen)
                dep.status != 3 && now >= dep.timeUtc && when {
                    target == null -> true
                    seen != null -> seen.distanceTo(target) < 60 - 3 * seen.slack
                    else -> !waitForFix
                }
            }
        }
        is Step.Ride -> {
            val ride = rideOf(step, chosen)
            (live != null && ride.shape.isNotEmpty() && live.distanceTo(ride.shape.last()) < 60 + slack) ||
                if (live != null && target != null) live.distanceTo(target) < 45 + slack else now >= ride.arr + 60
        }
        is Step.Taxi -> if (live != null && target != null) live.distanceTo(target) < 45 + slack else now >= step.leg.arr
        is Step.Cycle -> if (live != null && target != null) live.distanceTo(target) < 45 + slack else now >= step.leg.arr
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
    if (shape.isEmpty() || distanceToPath(fix.lat, fix.lon, shape) < 80 + 3 * fix.slack) return null
    val k = (i - 1 downTo 0).firstOrNull { steps[it] is Step.Walk || steps[it] is Step.Ride } ?: return null
    val walk = steps[k] as? Step.Walk ?: return null
    return k.takeIf { distanceToPath(fix.lat, fix.lon, walk.leg.shape) < ON_WALK_M - 3 * fix.slack }
}

private fun rideLeftBehind(step: Step.Ride, chosen: Map<Int, Int>, fix: Fix): Boolean {
    val shape = rideOf(step, chosen).shape
    if (shape.size < 2) return false
    return alongPath(fix.lat, fix.lon, shape) > pathLength(shape) - 60 - fix.slack
}

// Minutes until the ride reaches the rider's stop: the time its last stop gets below.
internal fun minutesToAlight(ride: Transit.Leg, r: Transit.Resolved, fix: Fix?, now: Long): Long {
    val at = rideTimes(ride, r, riderTrip(ride, r, fix, now), emptyMap(), fix, now).lastOrNull()?.utc ?: r.alightAt(ride)
    return ceil((at - now) / 60.0).toLong().coerceAtLeast(0)
}

// When the rider's bus gets to a stop of the ride, and whether that's its live time.
internal class StopTime(val utc: Long, val live: Boolean)

private const val NEAR_BUS_M = 400.0
private const val ELSEWHERE_M = 1_500.0

// The bus the rider is on. Aboard, it's the one of the ride's line reported nearest to them; when that isn't near and
// the planned one is reported far off, they're on a bus with no live data (null). Otherwise it's the planned one.
internal fun riderTrip(ride: Transit.Leg, r: Transit.Resolved, fix: Fix?, now: Long): Long? {
    val aboard = fix?.takeIf { it.isFresh(now) && it.aboard(ride.shape) } ?: return ride.tripId
    val stops = ride.stops.toHashSet()
    val buses = r.live.values.filter { it.lineId == ride.lineId && it.hasLocation && it.stopId in stops }
        .groupBy { it.tripId }.mapValues { (_, at) -> at.maxBy { it.sampleUtc } }
    fun away(a: Transit.Arrival) = metres(aboard.lat, aboard.lon, a.lat, a.lon)
    buses.values.minByOrNull(::away)?.takeIf { away(it) < NEAR_BUS_M }?.let { return it.tripId }
    return if (buses[ride.tripId]?.let { away(it) > ELSEWHERE_M } == true) null else ride.tripId
}

// Where the rider's bus was reported last, among its live times at the ride's stops.
internal fun busOn(ride: Transit.Leg, r: Transit.Resolved, trip: Long?): Transit.Arrival? {
    if (trip == null) return null
    val stops = ride.stops.toHashSet()
    return r.live.values.filter { it.tripId == trip && it.hasLocation && it.stopId in stops }.maxByOrNull { it.sampleUtc }
}

// When the rider's bus reaches each stop of the ride: its live time where there is one. Elsewhere it's in step with the
// nearest things known around the stop (live times, the rider aboard right now), at the plan's pace past the last of
// them, so a jam that holds the bus up moves every stop after it. With nothing known, the plan moved by the ride's delay.
internal fun rideTimes(
    ride: Transit.Leg, r: Transit.Resolved, trip: Long?, names: Map<Int, Transit.StopInfo>, fix: Fix?, now: Long,
): List<StopTime> {
    val n = ride.stops.size
    if (n == 0) return emptyList()
    val shape = ride.shape
    val length = if (shape.size >= 2) pathLength(shape) else 0.0
    // How far along the ride each stop is, 0 to 1: by the road where the stop's place is known, else by its count. Kept
    // in the ride's order, as two stops at one corner can land either way round on the road.
    var furthest = 0.0
    val share = ride.stops.mapIndexed { i, id ->
        val p = (names[id] ?: r.stops[id])?.point
        val f = if (length > 0 && p != null) (alongPath(p.first, p.second, shape) / length).coerceIn(0.0, 1.0)
            else if (n > 1) i.toDouble() / (n - 1) else 1.0
        f.coerceAtLeast(furthest).also { furthest = it }
    }
    val span = (ride.arr - ride.dep).coerceAtLeast(0)
    fun planned(f: Double) = ride.dep + (f * span).toLong()
    val live = ride.stops.map { id -> trip?.let { r.live[Transit.ArrivalKey(id, it)] }?.rtUtc?.takeIf { it > 0 } }
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
    steps: List<Step>,
    current: Int,
    r: Transit.Resolved,
    chosen: Map<Int, Int>,
    now: Long,
    fix: Fix?,
    canLocate: Boolean,
    startNow: Boolean = false,
): Int {
    if (steps.isEmpty()) return 0
    var i = current.coerceIn(0, steps.lastIndex)
    val live = fix?.takeIf { it.isFresh(now) }
    // The walks that tell walking from riding, for the step at k: all of the trip's, but for a rough fix only those
    // before it, as a rider on a later walk is past the ride.
    fun walks(k: Int) = steps.filterIndexed { j, s -> s is Step.Walk && (j < k || live == null || live.slack == 0.0) }
        .mapNotNull { (it as Step.Walk).leg.shape.takeIf { s -> s.isNotEmpty() } }
    if (live != null) walkedBack(steps, i, chosen, live)?.let { i = it }
    if (live != null) {
        for (k in steps.lastIndex downTo i + 1) {
            if (!onStep(steps[k], r, chosen, live, walks(k))) continue
            if ((i until k).any { j ->
                    steps[j] is Step.Ride && !done(steps[j], r, chosen, now, live, fix, false, walks(j)) &&
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
        if (!done(steps[i], r, chosen, now, live, seen, waitForFix, walks(i), startNow)) break
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
    ride: Transit.Leg,
    stops: Map<Int, Transit.StopInfo>,
    arrival: Transit.Arrival?,
    fix: Fix?,
    now: Long,
): Float {
    val shape = ride.shape
    if (shape.size >= 2) {
        // The rider's bus, once it's under way on the ride's route (raw positions can sit off the road a little): past
        // the boarding stop as its live time counts the stops, or, where it doesn't, by where it is on the road.
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
                val p = stops[id]?.point ?: continue
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

// How many of the ride's stops the bus is past, counted along the line: its next stop's place in the line
// against the boarding stop's, worked out from whichever stop of the ride the live time is for. Null when it doesn't say.
private fun stopsPast(ride: Transit.Leg, a: Transit.Arrival): Int? {
    val k = ride.stops.indexOf(a.stopId)
    if (k < 0 || a.stopIndex < 0 || a.nextStopIndex < 0) return null
    return a.nextStopIndex - (a.stopIndex - k)
}
