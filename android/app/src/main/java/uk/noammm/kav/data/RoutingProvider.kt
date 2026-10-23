package uk.noammm.kav.data

import uk.noammm.kav.data.Moovit.ALL_ROUTE_TYPES
import uk.noammm.kav.data.Moovit.LineInfo
import uk.noammm.kav.data.Moovit.Place
import uk.noammm.kav.data.Moovit.StopInfo
import uk.noammm.kav.data.Moovit.TIME_DEPARTURE

interface RoutingProvider {
    fun planItineraries(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        whenMs: Long = 0L,
        timeType: Int = TIME_DEPARTURE,
        routeTypes: List<Int> = ALL_ROUTE_TYPES,
        skipTaxi: Boolean = false,
        stopovers: List<Place> = emptyList(),
        toName: String? = null,
    ): Moovit.Plan
    fun loadDetails(
        pool: java.util.concurrent.ExecutorService, lineIds: Collection<Int>, stopIds: Collection<Int>,
        lines: MutableMap<Int, LineInfo>, stops: MutableMap<Int, StopInfo>, types: MutableMap<Int, Int>,
    )
    fun stopInfo(id: Int): StopInfo?
//    fun line(id: Int): LineInfo?
//    fun stop(id: Int): StopInfo?
//    fun stopName(id: Int): String?
//    fun routeType(agencyId: Int): Int
//    fun agencyName(agencyId: Int): String?

}