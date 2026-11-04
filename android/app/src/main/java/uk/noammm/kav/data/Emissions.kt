package uk.noammm.kav.data

import kotlin.math.roundToInt

// A trip's CO2e when its planner gives none, so every planner's trips have one: each ride's distance by its mode, at
// the UK government's 2026 conversion factors per passenger-km (DESNZ): an average local bus 101.51 g, national rail
// 30.92 g, light rail and tram 21.21 g, a regular taxi 148.61 g. Walking and cycling count nothing.
object Emissions {
    private const val BUS_G = 101.51
    private const val RAIL_G = 30.92
    private const val LIGHT_RAIL_G = 21.21
    private const val TAXI_G = 148.61
    // A ride without its road follows it about this much further than straight.
    private const val DETOUR = 1.25

    // The planner's own figure, else Kav's; -1 where a ride's distance can't be told.
    fun grams(it: Transit.Itinerary, r: Transit.Resolved): Int {
        if (it.co2g >= 0) return it.co2g
        var g = 0.0
        for (leg in it.legs) {
            val per = when (leg.kind) {
                Transit.LegKind.RIDE -> perKm(r.routeType(r.line(leg.lineId)?.agencyId ?: -1))
                Transit.LegKind.TAXI -> TAXI_G
                else -> continue
            }
            g += per * (km(leg, r) ?: return -1)
        }
        return g.roundToInt()
    }

    // By GTFS route type, the basic and the extended ones.
    internal fun perKm(type: Int): Double = when (type) {
        2, in 100..199 -> RAIL_G
        0, 1, 5, 6, 7, in 400..499, in 900..999, in 1300..1499 -> LIGHT_RAIL_G
        else -> BUS_G
    }

    // Along the ride's road where the plan has it, else through its stops, else from end to end.
    internal fun km(leg: Transit.Leg, r: Transit.Resolved): Double? {
        if (leg.shape.size >= 2) return path(leg.shape) / 1000
        val points = leg.stops.mapNotNull { r.stop(it)?.point }
        if (points.size >= 2) return path(points) * DETOUR / 1000
        if (leg.meters > 0) return leg.meters / 1000.0
        val from = r.stop(leg.fromStop)?.point ?: leg.taxiPickup
        val to = r.stop(leg.toStop)?.point ?: leg.taxiDropoff
        if (from != null && to != null) return path(listOf(from, to)) * DETOUR / 1000
        return null
    }

    private fun path(points: List<Pair<Double, Double>>): Double = points.zipWithNext().sumOf { (a, b) ->
        val k = Math.PI / 180
        val dLat = (b.first - a.first) * k
        val dLon = (b.second - a.second) * k * kotlin.math.cos((a.first + b.first) / 2 * k)
        6_371_000.0 * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }
}
