package uk.noammm.kav.ui

import uk.noammm.kav.data.Moovit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A position fix: where the phone was, when (epoch seconds), and how fast it was going. */
data class Fix(val lat: Double, val lon: Double, val at: Long, val speed: Float = 0f)

/** Older than this and a fix no longer says where you are, the clock takes over. */
private const val FRESH_S = 150L

internal fun Fix.isFresh(now: Long) = now - at <= FRESH_S
internal fun Fix.distanceTo(p: Pair<Double, Double>) = metres(lat, lon, p.first, p.second)

/* geometry on a small patch of the earth */

private class Flat(lat0: Double, lon0: Double) {
    private val kx = cos(Math.toRadians(lat0)) * 111_320.0
    private val ky = 110_574.0
    private val lat0 = lat0; private val lon0 = lon0
    fun x(lon: Double) = (lon - lon0) * kx
    fun y(lat: Double) = (lat - lat0) * ky
}

/** Metres from a point to the nearest point of a polyline. */
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

/** How far along a polyline, in metres from its start, the point nearest to (lat, lon) sits. */
internal fun alongPath(lat: Double, lon: Double, path: List<Pair<Double, Double>>): Double {
    if (path.size < 2) return 0.0
    val f = Flat(lat, lon)
    var best = Double.MAX_VALUE; var bestAlong = 0.0; var walked = 0.0
    for (i in 0 until path.lastIndex) {
        val ax = f.x(path[i].second); val ay = f.y(path[i].first)
        val bx = f.x(path[i + 1].second); val by = f.y(path[i + 1].first)
        val dx = bx - ax; val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        val t = if (len == 0.0) 0.0 else (((-ax) * dx + (-ay) * dy) / (len * len)).coerceIn(0.0, 1.0)
        val px = ax + dx * t; val py = ay + dy * t
        val d = sqrt(px * px + py * py)
        if (d < best) { best = d; bestAlong = walked + len * t }
        walked += len
    }
    return bestAlong
}

/** Where the polyline is heading at a point on it, in degrees from north. */
internal fun bearingAlong(lat: Double, lon: Double, path: List<Pair<Double, Double>>, lookahead: Double = 40.0): Float? {
    if (path.size < 2) return null
    val at = alongPath(lat, lon, path)
    val from = pointAlong(path, at) ?: return null
    val to = pointAlong(path, at + lookahead) ?: path.last()
    val back = pointAlong(path, max(0.0, at - lookahead)) ?: path.first()
    val (a, b) = if (metres(from.first, from.second, to.first, to.second) > 3.0) from to to else back to from
    return bearing(a.first, a.second, b.first, b.second)
}

/** The point a given number of metres along the polyline. */
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

/** Compass bearing from one point to another, degrees from north. */
internal fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Float {
    val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
    val dl = Math.toRadians(lo2 - lo1)
    val y = kotlin.math.sin(dl) * cos(p2)
    val x = cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
}

/** The polyline split where a point projects onto it: what is behind, what is ahead. */
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

/* which step of the journey the rider is on */

private fun rideOf(step: Step.Wait, chosen: Map<Int, Int>) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first
private fun rideOf(step: Step.Ride, chosen: Map<Int, Int>) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0).first

/** Where a step ends: the point that, reached, means the next one has begun. */
internal fun stepTarget(step: Step, r: Moovit.Resolved, chosen: Map<Int, Int>): Pair<Double, Double>? = when (step) {
    is Step.Start -> step.focus.firstOrNull()
    is Step.Walk -> r.stop(step.toStop)?.point ?: step.leg.shape.lastOrNull()
    is Step.Wait -> rideOf(step, chosen).let { r.stop(it.fromStop)?.point ?: it.shape.firstOrNull() }
    is Step.Ride -> rideOf(step, chosen).let { r.stop(it.toStop)?.point ?: it.shape.lastOrNull() }
    is Step.Taxi -> step.leg.taxiDropoff ?: step.leg.shape.lastOrNull()
    is Step.Cycle -> step.leg.shape.lastOrNull()
    is Step.Arrive -> step.focus.lastOrNull()
}

/** The departure the rider is waiting for: the live one where the service has it. */
private fun departureOf(step: Step.Wait, r: Moovit.Resolved, chosen: Map<Int, Int>): Moovit.Departure {
    val ride = rideOf(step, chosen)
    return r.departures(ride, step.wait).firstOrNull { it.tripId == ride.tripId }
        ?: Moovit.Departure(ride.tripId, ride.dep)
}

/**
 * Is the rider plainly on this step's ground? Used to catch up after a gap in fixes,
 * a phone that lost the sky inside a station and finds it again on the bus.
 */
private fun onStep(step: Step, r: Moovit.Resolved, chosen: Map<Int, Int>, fix: Fix): Boolean = when (step) {
    is Step.Walk -> {
        val start = step.leg.shape.firstOrNull()
        distanceToPath(fix.lat, fix.lon, step.leg.shape) < 45 && (start == null || fix.distanceTo(start) > 100)
    }
    is Step.Wait -> stepTarget(step, r, chosen)?.let { fix.distanceTo(it) < 40 } == true
    is Step.Ride -> {
        val ride = rideOf(step, chosen)
        val shape = ride.shape
        val vehicle = r.arrival(ride)?.takeIf { it.hasLocation }
        shape.size >= 2 && distanceToPath(fix.lat, fix.lon, shape) < 50 &&
            alongPath(fix.lat, fix.lon, shape) > 150 &&
            (fix.speed > 5f || (vehicle != null && metres(fix.lat, fix.lon, vehicle.lat, vehicle.lon) < 80))
    }
    is Step.Arrive -> stepTarget(step, r, chosen)?.let { fix.distanceTo(it) < 40 } == true
    else -> false
}

/** Is this step over? With a fresh fix the ground decides; without one, the clock. */
private fun done(step: Step, r: Moovit.Resolved, chosen: Map<Int, Int>, now: Long, fix: Fix?): Boolean {
    val target = stepTarget(step, r, chosen)
    return when (step) {
        is Step.Start -> now >= step.time || (fix != null && target != null && fix.distanceTo(target) > 60)
        is Step.Walk -> if (fix != null && target != null) fix.distanceTo(target) < 40 else now >= step.leg.arr
        is Step.Wait -> {
            val ride = rideOf(step, chosen)
            if (fix != null && target != null) {
                val vehicle = r.arrival(ride)
                val left = vehicle != null && vehicle.hasLocation && vehicle.stopIndex >= 0 && vehicle.nextStopIndex > vehicle.stopIndex
                (fix.distanceTo(target) > 80 && distanceToPath(fix.lat, fix.lon, ride.shape) < 60) ||
                    (left && fix.distanceTo(target) > 40)
            } else {
                val dep = departureOf(step, r, chosen)
                dep.status != 3 && now >= dep.timeUtc
            }
        }
        is Step.Ride -> if (fix != null && target != null) fix.distanceTo(target) < 45 else now >= rideOf(step, chosen).arr + 60
        is Step.Taxi -> if (fix != null && target != null) fix.distanceTo(target) < 45 else now >= step.leg.arr
        is Step.Cycle -> if (fix != null && target != null) fix.distanceTo(target) < 45 else now >= step.leg.arr
        is Step.Arrive -> false
    }
}

/**
 * The step the rider is on, given where they were last seen and what time it is.
 * Never goes backwards: a step once passed stays passed, however the fixes wander.
 */
internal fun journeyProgress(
    steps: List<Step>,
    current: Int,
    r: Moovit.Resolved,
    chosen: Map<Int, Int>,
    now: Long,
    fix: Fix?,
): Int {
    if (steps.isEmpty()) return 0
    var i = current.coerceIn(0, steps.lastIndex)
    val live = fix?.takeIf { it.isFresh(now) }
    if (live != null) {
        for (k in steps.lastIndex downTo i + 1) {
            if (onStep(steps[k], r, chosen, live)) { i = k; break }
        }
    }
    while (i < steps.lastIndex && done(steps[i], r, chosen, now, live)) i++
    return i
}

/**
 * How many of a ride's stops are behind the rider. The tracked vehicle knows best;
 * failing that, the phone's own place along the route; failing both, nothing is greyed.
 */
internal fun stopsPassed(
    ride: Moovit.Leg,
    stops: Map<Int, Moovit.StopInfo>,
    arrival: Moovit.Arrival?,
    fix: Fix?,
    now: Long,
): Int {
    if (arrival != null && arrival.hasLocation && arrival.nextStopIndex >= 0 && arrival.stopIndex >= 0 &&
        arrival.nextStopIndex > arrival.stopIndex
    ) {
        return (arrival.nextStopIndex - arrival.stopIndex).coerceIn(0, ride.stops.size)
    }
    val live = fix?.takeIf { it.isFresh(now) } ?: return -1
    val shape = ride.shape
    if (shape.size < 2 || distanceToPath(live.lat, live.lon, shape) > 80) return -1
    val along = alongPath(live.lat, live.lon, shape)
    if (along < 60) return -1
    var passed = 0
    for (id in ride.stops) {
        val p = stops[id]?.point ?: continue
        if (alongPath(p.first, p.second, shape) <= along + 25) passed++ else break
    }
    return passed
}
