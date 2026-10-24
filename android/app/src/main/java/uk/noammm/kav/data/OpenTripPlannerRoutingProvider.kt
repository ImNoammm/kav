package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.data.Moovit.LineInfo
import uk.noammm.kav.data.Moovit.StopInfo
import uk.noammm.kav.data.Moovit.agencyRouteType
import uk.noammm.kav.data.Moovit.lineInfo
import uk.noammm.kav.data.Moovit.stopInfo
import java.net.HttpURLConnection
import java.net.URL
import kotlin.String
import kotlin.collections.ArrayList
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.collections.set


class OpenTripPlannerRoutingProvider(val endpoint: String): RoutingProvider{
    private val base = endpoint.trim().trimEnd('/')
    private val stopCache = java.util.concurrent.ConcurrentHashMap<Int, Moovit.StopInfo>()
    private val agencyById = java.util.concurrent.ConcurrentHashMap<Int, String> ()
    private val lineById = java.util.concurrent.ConcurrentHashMap<Int, Moovit.LineInfo> ()

    private val liveByKey = java.util.concurrent.ConcurrentHashMap<Moovit.ArrivalKey, Moovit.Arrival> ()
    private val routeTypeByAgency = java.util.concurrent.ConcurrentHashMap<Int, Int> ()
    fun formatEpochMillis(epochMillis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            .withZone(ZoneId.of("Asia/Jerusalem"))

        return formatter.format(Instant.ofEpochMilli(epochMillis))
    }
    fun decodePolyline(encoded: String?): List<Pair<Double, Double>> {
        if (encoded.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Pair<Double, Double>>(encoded.length / 4)
        var i = 0; var lat = 0; var lon = 0
        while (i < encoded.length) {
            var shift = 0; var result = 0; var b: Int
            do {
                if (i >= encoded.length) return out
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift); shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do {
                if (i >= encoded.length) return out
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift); shift += 5
            } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(lat / 1e5 to lon / 1e5)
        }
        return out
    }
    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(s).toEpochSecond()
        } catch (e: Exception) {
            null
        }
    }
    private fun parseQuay(q: JSONObject?): Moovit.StopInfo?{
        if (q!=null) {
            val idAsString = q.getString("id")
            val id = idAsString.split(":")[1].toInt()
            val publicCode = q.getString("description")
            val latitude = q.getDouble("latitude")
            val longitude = q.getDouble("longitude")
            val name = q.getString("name")
            return Moovit.StopInfo(
                id = id, name = name, lat = latitude, lon = longitude, code = publicCode

            )
        }
        return null

    }
    fun modeToRouteType(mode: String): Int = when (mode.lowercase(Locale.US)) {
        "tram" -> 0
        "metro" -> 1
        "rail", "monorail" -> 2
        "bus", "coach", "trolleybus" -> 3
        "water" -> 4
        "cableway" -> 5
        "lift" -> 6
        "funicular" -> 7
        else -> 3
    }
    private fun parseLeg(l: JSONObject): Moovit.Leg? {
        val mode = l.optString("mode")
        val dep = parseTime(l.optString("aimedStartTime")) ?: 0
        val arr = parseTime(l.optString("aimedEndTime")) ?: 0
        val meters = l.optDouble("distance", 0.0).toInt()
        val shape = decodePolyline(l.optJSONObject("pointsOnLink")?.optString("points"))
        val from = l.optJSONObject("fromPlace")
        val to = l.optJSONObject("toPlace")
        val fromQuay = parseQuay(from?.optJSONObject("quay"))
        val toQuay = parseQuay(to?.optJSONObject("quay"))
        if (fromQuay?.id!=null) stopCache[fromQuay.id]=fromQuay
        if (toQuay?.id!=null) stopCache[toQuay.id]=toQuay


        if (!l.optBoolean("ride", false)) {
            val kind = when (mode) {
                "bicycle" -> Moovit.LegKind.BIKE
                "car", "taxi", "car_pickup", "car_rental", "car_park" -> Moovit.LegKind.TAXI
                else -> Moovit.LegKind.WALK
            }

            return Moovit.Leg(
                kind = kind, dep = dep, arr = arr,
                fromStop = fromQuay?.id ?: -1, toStop = toQuay?.id ?: -1,
                meters = meters, shape = shape,
            )
        }

        val line = l.optJSONObject("line")
        val lineId = line?.optString("id")?.split(":")[1]?.toInt()
        val authority = l.optJSONObject("authority")
        val agency = authority?.optString("name")
        val agencyId = authority?.optString("id")?.split(":")[1]?.toInt()
        val routeType = modeToRouteType(mode)
        if (agency!=null && agencyId !=null) agencyById[agencyId] = agency
         if (agencyId!=null) routeTypeByAgency[agencyId] = routeType
//        val lineQuays = line?.optJSONArray("quays")
        val destination_name = l.optJSONObject("toEstimatedCall")?.optJSONObject("destinationDisplay")?.optString("frontText")?.replace('_', ' ')
//        val destination_name = destination?.optString("name")
        if (lineId!=null && line!=null) lineById[lineId] = Moovit.LineInfo(
            groupId = lineId,
            number = line.optString("publicCode").orEmpty().ifBlank { line?.optString("name").orEmpty() },
            agencyId = agencyId ?: -1,
            origin = fromQuay?.name.orEmpty(),
            destination = destination_name.orEmpty(),
            caption = line.optString("name").orEmpty(),
        )


        val stops = ArrayList<Int>()
        if (fromQuay?.id != null) stops.add(fromQuay.id)
        val inter = l.optJSONArray("intermediateQuays")
        for (i in 0 until (inter?.length() ?: 0)) {
            val q = parseQuay(inter?.optJSONObject(i)) ?: continue
            val qid = q.id
            if (qid > 0) { stopCache[qid] =q}
            stops.add(qid)
        }
        if (toQuay?.id != null) stops.add(toQuay.id)

        val tripIdString = l.optJSONObject("serviceJourney")?.optString("id")?.split(':')[1]
        val tripId = (tripIdString?.split("_")[0]?.toLong()?.shr(32)?.or(tripIdString.split("_")[1].toLong()) )?: 0L
        val realtime = l.optBoolean("realtime", false)
        val expected = parseTime(l.optString("expectedStartTime")) ?: 0
        val rtUtc = if (realtime && expected > 0 && expected != dep) expected else 0L
        // The live layer is keyed by the stop the rider boards at, as Moovit's is.
        if (fromQuay?.id != null &&  lineId != null)
            liveByKey[Moovit.ArrivalKey(fromQuay.id, tripId)] = Moovit.Arrival(
                stopId = fromQuay.id, lineId = lineId, tripId = tripId,
                staticUtc = dep, rtUtc = rtUtc, statisticalUtc = 0,
                status = 0, certainty = 0, traffic = 0, frequency = false,
                rtDropped = false, tracked = realtime,
                lat = fromQuay.lat,
                lon = fromQuay.lon
            )
        if (lineId!=null )
        return Moovit.Leg(
            kind = Moovit.LegKind.RIDE,
            lineId = lineId,
            tripId = tripId,
            dep = dep, arr = arr, stops = stops,
            fromStop = fromQuay?.id ?: -1, toStop = toQuay?.id ?:-1,
            meters = meters,
            nextDeps = listOf(Moovit.Departure(tripId, dep, rtUtc = rtUtc, vehicleStatus = 0)),
            shortName = lineById[lineId]?.number.orEmpty(),
            shape = shape,
        )
        return null
    }

    private fun graphql(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().put("query", query).put("variables", variables)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val c = (URL(base).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 8000; readTimeout = 25000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) { null }
            if (code !in 200..299) {
                val message =  "OpenTripPlanner HTTP $code"
                throw Moovit.PlannerRefusal(code, message, "")
            }
            if (o == null) throw Moovit.PlannerRefusal(0, "OpenTripPlanner returned no JSON", "")
            if (o.optJSONObject("data") == null) {
                throw Moovit.PlannerRefusal(
                    0,
                     "OpenTripPlanner rejected the query",
                    ""
                )
            }
            o.optJSONObject("data") ?: JSONObject()
        } finally {
            c.disconnect()
        }
    }
    private fun planTripRequest(
       from: Pair<Double, Double>,
       to: Pair<Double, Double>,
       whenMs: Long,
       timeType: Int,
       routeTypes: List<Int>,
       skipTaxi: Boolean,
       stopovers: List<Moovit.Place>,
       toName: String?
    ): JSONObject{
        val args = JSONObject().put(
            "from", JSONObject()
                .put("coordinates", JSONObject()
                    .put("latitude", from.first)
                    .put("longitude", from.second)
                )
            )
            .put(
                "to", JSONObject()
                    .put("coordinates", JSONObject()
                        .put("latitude", to.first)
                    .put("longitude", to.second)
                    )
            )
            .put("dateTime", formatEpochMillis(whenMs))
            .put("arriveBy", timeType==Moovit.TIME_ARRIVAL)
        val response = graphql(TRIP_FIELDS, args)
        return response


    }
    private fun parseItinerary(pattern: JSONObject, index: Int): Moovit.Itinerary? {
        val legsArr = pattern.optJSONArray("legs") ?: return null
        val legs = ArrayList<Moovit.Leg>()
        for (i in 0 until legsArr.length()) {
            parseLeg(legsArr.optJSONObject(i) ?: continue)?.let { legs.add(it) }
        }
        if (legs.isEmpty()) return null
        val dep = parseTime(pattern.optString("aimedStartTime")) ?: legs.firstOrNull { it.dep > 0 }?.dep ?: 0
        val arr = parseTime(pattern.optString("aimedEndTime")) ?: legs.lastOrNull { it.arr > 0 }?.arr ?: 0
        return Moovit.Itinerary(
            guid = "otp-${dep}-${arr}-$index",
            group = -1,
            legs = legs,
            dep = dep,
            arr = arr,
        )
    }
    private fun refusal(errors: JSONArray?): Moovit.PlannerRefusal {
        val first = errors?.optJSONObject(0)
        val name = first?.optString("code").orEmpty()
        val detail = first?.optString("description").orEmpty()
        val code = when (name) {
            "walkingBetterThanTransit" -> Moovit.PLAN_TOO_CLOSE
            "noTransitConnection", "noTransitConnectionInSearchWindow", "noDirectModeConnection" -> Moovit.PLAN_NO_ROUTES
            "locationNotFound", "noStopsInRange", "outsideBounds", "outsideServicePeriod" -> Moovit.PLAN_NO_COVERAGE
            else -> Moovit.PLAN_NO_ROUTES
        }
        return Moovit.PlannerRefusal(code, name.ifBlank { "No trip patterns" }, detail)
    }

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
        val response = planTripRequest( from, to ,
            whenMs, timeType, routeTypes, skipTaxi, stopovers, toName)
        val trip = response.optJSONObject("trip") ?: return Moovit.Plan()
        val patterns = trip.optJSONArray("tripPatterns") ?: JSONArray()
        val itineraries = ArrayList<Moovit.Itinerary>()
        for (i in 0 until patterns.length()) {
            val pattern = patterns.optJSONObject(i) ?: continue
            parseItinerary(pattern, i)?.let { itineraries.add(it) }
        }
        if (itineraries.isEmpty()) throw refusal(trip.optJSONArray("routingErrors"))
        return Moovit.Plan(itineraries)

    }

    override fun loadDetails(
        pool: ExecutorService,
        lineIds: Collection<Int>,
        stopIds: Collection<Int>,
        lines: MutableMap<Int, Moovit.LineInfo>,
        stops: MutableMap<Int, StopInfo>,
        types: MutableMap<Int, Int>
    ) {
        val lineJobs = lineIds.map { id -> id to pool.submit<LineInfo?> { lineInfo(id)} }
        val stopJobs = stopIds.map { id -> id to pool.submit<StopInfo?> { stopInfo(id)} }
        for ((id, f) in lineJobs) runCatching { f.get() }.getOrNull()?.let { lines[id] = it }
        for ((id, f) in stopJobs) runCatching { f.get() }.getOrNull()?.let { stops[id] = it }
        val agencies = lines.values.map { it.agencyId }.distinct().filter { it !in types }
        val typeJobs = agencies.map { a -> a to pool.submit<Int> { routeTypeByAgency[a] } }
        for ((a, f) in typeJobs) types[a] = runCatching { f.get() }.getOrNull() ?: 3
    }
    override fun stopInfo(id: Int) : StopInfo?{
        stopCache[id]?.let{return stopCache[id]}
        val response = graphql(
            STOP_FIELDS, variables = JSONObject().put("id", "1:${id}")
        )
        val quay = parseQuay(response.getJSONObject("quay"))
        if (quay==null) return null
        stopCache[id]=quay
        return quay
    }
    fun lineInfo(id: Int) : LineInfo? {
        lineById[id]?.let{return lineById[id]}
        return null
    }

//    override fun line(id: Int): Moovit.LineInfo? {
//        return lineById[id]
//    }
//
//    override fun stop(id: Int): StopInfo? {
//        return stopCache[id]
//    }
//
//    override fun stopName(id: Int): String? {
//        return stopCache[id]?.name
//    }
//
//    override fun routeType(agencyId: Int): Int {
//        return routeTypeByAgency[agencyId] ?: -1
//    }
//
    override fun agencyName(agencyId: Int): String? {
        return agencyById[agencyId]
    }
    val STOP_FIELDS ="""
query quay(${'$'}id: String!) {
  quay(id: ${'$'}id) {
    id
    name
    description
    latitude
    longitude
  }
}
""".trimIndent()
    val LINE_FIELDS ="""
query quay(${'$'}id: String!) {
  quay(id: ${'$'}id) {
    id
    name
    description
    latitude
    longitude
  }
}
""".trimIndent()
    val TRIP_FIELDS = """
       query trip(${'$'}accessEgressPenalty: [PenaltyForStreetMode!], ${'$'}alightSlackDefault: Int, ${'$'}alightSlackList: [TransportModeSlack], ${'$'}arriveBy: Boolean, ${'$'}banned: InputBanned, ${'$'}bicycleOptimisationMethod: BicycleOptimisationMethod, ${'$'}bikeSpeed: Float, ${'$'}boardSlackDefault: Int, ${'$'}boardSlackList: [TransportModeSlack], ${'$'}bookingTime: DateTime, ${'$'}dateTime: DateTime, ${'$'}filters: [TripFilterInput!], ${'$'}from: Location!, ${'$'}ignoreRealtimeUpdates: Boolean, ${'$'}includePlannedCancellations: Boolean, ${'$'}includeRealtimeCancellations: Boolean, ${'$'}itineraryFilters: ItineraryFilters, ${'$'}locale: Locale, ${'$'}maxAccessEgressDurationForMode: [StreetModeDurationInput!], ${'$'}maxDirectDurationForMode: [StreetModeDurationInput!], ${'$'}maximumAdditionalTransfers: Int, ${'$'}maximumTransfers: Int, ${'$'}modes: Modes, ${'$'}numTripPatterns: Int, ${'$'}pageCursor: String, ${'$'}relaxTransitGroupPriority: RelaxCostInput, ${'$'}searchWindow: Int, ${'$'}timetableView: Boolean, ${'$'}to: Location!, ${'$'}transferPenalty: Int, ${'$'}transferSlack: Int, ${'$'}triangleFactors: TriangleFactors, ${'$'}useBikeRentalAvailabilityInformation: Boolean, ${'$'}via: [TripViaLocationInput!], ${'$'}waitReluctance: Float, ${'$'}walkReluctance: Float, ${'$'}walkSpeed: Float, ${'$'}wheelchairAccessible: Boolean, ${'$'}whiteListed: InputWhiteListed) {
  trip(
    accessEgressPenalty: ${'$'}accessEgressPenalty
    alightSlackDefault: ${'$'}alightSlackDefault
    alightSlackList: ${'$'}alightSlackList
    arriveBy: ${'$'}arriveBy
    banned: ${'$'}banned
    bicycleOptimisationMethod: ${'$'}bicycleOptimisationMethod
    bikeSpeed: ${'$'}bikeSpeed
    boardSlackDefault: ${'$'}boardSlackDefault
    boardSlackList: ${'$'}boardSlackList
    bookingTime: ${'$'}bookingTime
    dateTime: ${'$'}dateTime
    filters: ${'$'}filters
    from: ${'$'}from
    ignoreRealtimeUpdates: ${'$'}ignoreRealtimeUpdates
    includePlannedCancellations: ${'$'}includePlannedCancellations
    includeRealtimeCancellations: ${'$'}includeRealtimeCancellations
    itineraryFilters: ${'$'}itineraryFilters
    locale: ${'$'}locale
    maxAccessEgressDurationForMode: ${'$'}maxAccessEgressDurationForMode
    maxDirectDurationForMode: ${'$'}maxDirectDurationForMode
    maximumAdditionalTransfers: ${'$'}maximumAdditionalTransfers
    maximumTransfers: ${'$'}maximumTransfers
    modes: ${'$'}modes
    numTripPatterns: ${'$'}numTripPatterns
    pageCursor: ${'$'}pageCursor
    relaxTransitGroupPriority: ${'$'}relaxTransitGroupPriority
    searchWindow: ${'$'}searchWindow
    timetableView: ${'$'}timetableView
    to: ${'$'}to
    transferPenalty: ${'$'}transferPenalty
    transferSlack: ${'$'}transferSlack
    triangleFactors: ${'$'}triangleFactors
    useBikeRentalAvailabilityInformation: ${'$'}useBikeRentalAvailabilityInformation
    via: ${'$'}via
    waitReluctance: ${'$'}waitReluctance
    walkReluctance: ${'$'}walkReluctance
    walkSpeed: ${'$'}walkSpeed
    wheelchairAccessible: ${'$'}wheelchairAccessible
    whiteListed: ${'$'}whiteListed
  ) {
    previousPageCursor
    nextPageCursor
    tripPatterns {
      aimedStartTime
      aimedEndTime
      expectedEndTime
      expectedStartTime
      duration
      distance
      generalizedCost
      legs {
        serviceJourney {
					id
        }
        ride
        id
        toEstimatedCall{
          destinationDisplay {
            frontText
          }
        }
        mode
        aimedStartTime
        aimedEndTime
        expectedEndTime
        expectedStartTime
        realtime
        distance
        duration
        generalizedCost
        fromPlace {
          name
          quay {
            id
            name
            description
            latitude
            longitude
          }
        }

        toPlace {
          name
          quay {
            id
            name
            description
            latitude
            longitude
          }
        }
        toEstimatedCall {
          destinationDisplay {
            frontText
          }
        }
        line {
          publicCode
          name
          id
          presentation {
            colour
          }
          description
          

          
        }
        intermediateQuays {
          id
          name
          latitude
          longitude
          description
        }
        authority {
          name
          id
        }
        pointsOnLink {
          points
        }
        interchangeTo {
          staySeated
        }
        interchangeFrom {
          staySeated
        }
      }
      systemNotices {
        tag
      }
    }
  }
}
        """.trimIndent()

}