package uk.noammm.kav.ui

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
private fun done(
    step: Step, r: Moovit.Resolved, chosen: Map<Int, Int>, now: Long, live: Fix?, seen: Fix?, waitForFix: Boolean,
    walks: List<List<Pair<Double, Double>>>,
): Boolean {
    val target = stepTarget(step, r, chosen)
    return when (step) {
        is Step.Start -> now >= step.time || (live != null && target != null && live.distanceTo(target) > 60)
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

// Minutes until the ride reaches the rider's stop: Moovit's live time there, and on board the share of the route still
// ahead of the ride's planned time; otherwise the plan moved by how far the ride runs from it. When the two disagree
// on board, where the rider is wins: they may be on another bus of the line, or the live feed may lag.
internal fun minutesToAlight(ride: Moovit.Leg, r: Moovit.Resolved, fix: Fix?, now: Long): Long {
    val live = r.alightLiveAt(ride)?.let { ceil((it - now) / 60.0).toLong().coerceAtLeast(0) }
    val aboard = fix?.takeIf { it.isFresh(now) && it.aboard(ride.shape) }
    val length = pathLength(ride.shape)
    val share = if (aboard != null && length > 0) {
        val ahead = (length - alongPath(aboard.lat, aboard.lon, ride.shape)).coerceAtLeast(0.0)
        ceil(ahead / length * (ride.arr - ride.dep) / 60.0).toLong()
    } else null
    return when {
        live != null && share != null -> if (kotlin.math.abs(live - share) <= 5 + share * 3 / 10) live else share
        live != null -> live
        share != null -> share
        else -> ceil((r.alightAt(ride) - now) / 60.0).toLong().coerceAtLeast(0)
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
                    steps[j] is Step.Ride && !done(steps[j], r, chosen, now, live, fix, false, walks) &&
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
        if (!done(steps[i], r, chosen, now, live, seen, waitForFix, walks)) break
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
        val vehicle = arrival?.takeIf {
            it.hasLocation && it.stopIndex >= 0 && it.nextStopIndex > it.stopIndex &&
                distanceToPath(it.lat, it.lon, shape) < 80
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
    if (arrival != null && arrival.hasLocation && arrival.nextStopIndex >= 0 && arrival.stopIndex >= 0 &&
        arrival.nextStopIndex > arrival.stopIndex
    ) {
        return (arrival.nextStopIndex - arrival.stopIndex).coerceIn(0, ride.stops.size).toFloat()
    }
    return -1f
}
