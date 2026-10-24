package uk.noammm.kav.data

import java.util.concurrent.ExecutorService

class MoovitRoutingProvider(val s: MoovitSession): RoutingProvider {
    override fun planItineraries(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        whenMs: Long,
        timeType: Int,
        routeTypes: List<Int>,
        skipTaxi: Boolean,
        stopovers: List<Moovit.Place>,
        toName: String?
    ): Moovit.Plan {
        return Moovit.planItineraries(s, from, to, whenMs, timeType, routeTypes, skipTaxi, stopovers, toName )
    }

    override fun loadDetails(
        pool: ExecutorService,
        lineIds: Collection<Int>,
        stopIds: Collection<Int>,
        lines: MutableMap<Int, Moovit.LineInfo>,
        stops: MutableMap<Int, Moovit.StopInfo>,
        types: MutableMap<Int, Int>
    ) {
        return Moovit.loadDetails(
            pool, s, lineIds, stopIds, lines, stops, types
        )
    }

    override fun stopInfo(id: Int): Moovit.StopInfo? {
        return Moovit.stopInfo(s, id)
    }

    override fun agencyName(agencyId: Int): String? {
        return Moovit.agencyName(agencyId)
    }
}