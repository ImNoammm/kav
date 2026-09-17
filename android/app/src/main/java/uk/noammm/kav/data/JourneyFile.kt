package uk.noammm.kav.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ActiveJourney
import java.io.File

/**
 * The trip being navigated, kept on disk so it survives the process.
 *
 * Android ends Kav whenever it pleases once it is in the background, and a rider
 * who reopens it mid-trip wants their trip back, not the search box. What is kept
 * is the plan itself and the names and geometry already resolved for it, so the
 * card comes back whole with no signal at all. What is not kept is the live
 * layer: it is stale the moment it is written, and the same loop that always
 * refreshed it refreshes it again.
 *
 * A file this Kav cannot read — corrupt, or written by a different Kav — is a
 * fresh start, never a crash.
 */
object JourneyFile {
    private const val NAME = "journey.json"

    /**
     * How long past its planned arrival a trip is still offered back. Trips run
     * late, they do not run into tomorrow: reopening the app the next morning
     * should not lead with yesterday's ride home.
     */
    private const val KEEP_S = 3 * 3600L

    /**
     * The last content written, as a cheap fingerprint: the trip's identity, the
     * rider's choices, the step, and how much of the name and geometry tables has
     * arrived. Everything in it moves only when the file should, so the live
     * refresh rewriting the journey every poll costs no writes at all.
     */
    @Volatile private var lastKey: Int? = null

    private fun key(journey: ActiveJourney, step: Int): Int = listOf(
        System.identityHashCode(journey.trip), journey.chosen, step,
        journey.fromLabel, journey.toLabel,
        journey.resolved.lines.size, journey.resolved.stops.size,
        journey.resolved.routeTypes.size, journey.resolved.shapes.size,
    ).hashCode()

    private fun file(ctx: Context) = File(ctx.filesDir, NAME)

    /** What was being navigated when the process last ran, and the step it was at. */
    fun load(ctx: Context): Pair<ActiveJourney, Int>? {
        val f = file(ctx)
        val o = try { JSONObject(f.readText()) } catch (e: Exception) { return null }
        val now = System.currentTimeMillis() / 1000
        val arr = o.optJSONObject("trip")?.optLong("arr") ?: 0L
        if (now > maxOf(arr, o.optLong("saved")) + KEEP_S) { f.delete(); return null }
        val out = parse(o)
        if (out == null) f.delete() else lastKey = key(out.first, out.second)
        return out
    }

    /** Writes only when something kept actually changed; the live layer never counts. */
    fun save(ctx: Context, journey: ActiveJourney, step: Int) {
        val k = key(journey, step)
        if (k == lastKey) return
        val body = json(journey, step).put("saved", System.currentTimeMillis() / 1000).toString()
        val tmp = File(ctx.filesDir, "$NAME.tmp")
        try {
            tmp.writeText(body)
            val f = file(ctx)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            lastKey = k
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    /** The trip was ended on purpose: nothing to come back to. */
    fun clear(ctx: Context) {
        file(ctx).delete()
        lastKey = null
    }

    /* The codec. Plain values in, plain values out, so a test can hold it whole. */

    internal fun json(journey: ActiveJourney, step: Int): JSONObject = JSONObject()
        .put("step", step)
        .put("from", journey.fromLabel)
        .put("to", journey.toLabel)
        .put("chosen", JSONObject().apply { journey.chosen.forEach { (leg, opt) -> put(leg.toString(), opt) } })
        .put("trip", json(journey.trip))
        .put("lines", JSONObject().apply { journey.resolved.lines.forEach { (id, l) -> put(id.toString(), json(l)) } })
        .put("stops", JSONObject().apply { journey.resolved.stops.forEach { (id, s) -> put(id.toString(), json(s)) } })
        .put("types", JSONObject().apply { journey.resolved.routeTypes.forEach { (a, t) -> put(a.toString(), t) } })
        .put("shapes", JSONObject().apply { journey.resolved.shapes.forEach { (id, s) -> put(id.toString(), coords(s)) } })

    internal fun parse(o: JSONObject): Pair<ActiveJourney, Int>? = try {
        val journey = ActiveJourney(
            trip = itinerary(o.getJSONObject("trip")),
            resolved = Moovit.Resolved(
                lines = keyed(o.optJSONObject("lines")) { line(it) },
                stops = keyed(o.optJSONObject("stops")) { stop(it) },
                routeTypes = counts(o.optJSONObject("types")),
                shapes = paths(o.optJSONObject("shapes")),
            ),
            fromLabel = o.optString("from"),
            toLabel = o.optString("to"),
            chosen = counts(o.optJSONObject("chosen")),
        )
        journey to o.optInt("step")
    } catch (e: Exception) { null }

    private fun json(t: Moovit.Itinerary): JSONObject = JSONObject()
        .put("guid", t.guid).put("group", t.group)
        .put("legs", JSONArray().apply { t.legs.forEach { put(json(it)) } })
        .put("dep", t.dep).put("arr", t.arr)
        .put("fare", t.fare).put("cur", t.currency).put("co2", t.co2g)
        .put("acc", t.accessible).put("tags", JSONArray(t.tags))
        .put("sec", t.section).put("secId", t.sectionId)

    private fun itinerary(o: JSONObject) = Moovit.Itinerary(
        guid = o.optString("guid"), group = o.optInt("group"),
        legs = objects(o.optJSONArray("legs")).map { leg(it) },
        dep = o.optLong("dep"), arr = o.optLong("arr"),
        fare = o.optInt("fare", -1), currency = o.optString("cur"),
        co2g = o.optInt("co2", -1), accessible = o.optBoolean("acc"),
        tags = strings(o.optJSONArray("tags")),
        section = o.optString("sec"), sectionId = o.optInt("secId", -1),
    )

    private fun json(l: Moovit.Leg): JSONObject {
        val o = JSONObject()
            .put("kind", l.kind.name)
            .put("line", l.lineId).put("trip", l.tripId)
            .put("dep", l.dep).put("arr", l.arr)
            .put("stops", JSONArray(l.stops))
            .put("fromStop", l.fromStop).put("toStop", l.toStop)
            .put("m", l.meters)
            .put("deps", JSONArray().apply { l.nextDeps.forEach { put(json(it)) } })
            .put("name", l.shortName).put("pathway", l.pathway)
            .put("fare", l.fare).put("cur", l.currency)
            .put("shape", coords(l.shape))
            .put("alertCat", l.alertCategory).put("alertText", l.alertText)
            .put("alts", JSONArray().apply { l.alternatives.forEach { put(json(it)) } })
            .put("altLines", JSONArray(l.alternativeLineIds))
        l.taxiPickup?.let { o.put("pickup", coords(listOf(it))) }
        l.taxiDropoff?.let { o.put("dropoff", coords(listOf(it))) }
        return o
    }

    private fun leg(o: JSONObject): Moovit.Leg = Moovit.Leg(
        kind = Moovit.LegKind.valueOf(o.getString("kind")),
        lineId = o.optInt("line", -1), tripId = o.optLong("trip"),
        dep = o.optLong("dep"), arr = o.optLong("arr"),
        stops = ints(o.optJSONArray("stops")),
        fromStop = o.optInt("fromStop", -1), toStop = o.optInt("toStop", -1),
        meters = o.optInt("m"),
        nextDeps = objects(o.optJSONArray("deps")).map { departure(it) },
        shortName = o.optString("name"), pathway = o.optBoolean("pathway"),
        fare = o.optInt("fare", -1), currency = o.optString("cur"),
        shape = points(o.optJSONArray("shape")),
        alertCategory = o.optInt("alertCat"), alertText = o.optString("alertText"),
        taxiPickup = o.optJSONArray("pickup")?.let { points(it).firstOrNull() },
        taxiDropoff = o.optJSONArray("dropoff")?.let { points(it).firstOrNull() },
        alternatives = objects(o.optJSONArray("alts")).map { leg(it) },
        alternativeLineIds = ints(o.optJSONArray("altLines")),
    )

    private fun json(d: Moovit.Departure): JSONObject = JSONObject()
        .put("trip", d.tripId).put("static", d.staticUtc)
        .put("rt", d.rtUtc).put("stat", d.statisticalUtc)
        .put("status", d.status).put("cert", d.certainty).put("traffic", d.traffic)
        .put("freq", d.frequency).put("dropped", d.rtDropped)
        .put("veh", d.vehicleStatus).put("alert", d.alert)

    private fun departure(o: JSONObject) = Moovit.Departure(
        tripId = o.optLong("trip"), staticUtc = o.optLong("static"),
        rtUtc = o.optLong("rt"), statisticalUtc = o.optLong("stat"),
        status = o.optInt("status"), certainty = o.optInt("cert"),
        traffic = o.optInt("traffic"), frequency = o.optBoolean("freq"),
        rtDropped = o.optBoolean("dropped"), vehicleStatus = o.optInt("veh"),
        alert = o.optInt("alert"),
    )

    private fun json(l: Moovit.LineInfo): JSONObject = JSONObject()
        .put("group", l.groupId).put("number", l.number).put("agency", l.agencyId)
        .put("origin", l.origin).put("dest", l.destination).put("caption", l.caption)

    private fun line(o: JSONObject) = Moovit.LineInfo(
        o.optInt("group", -1), o.optString("number"), o.optInt("agency", -1),
        o.optString("origin"), o.optString("dest"), o.optString("caption"),
    )

    /** A stop with no known position writes none: JSON has no NaN to give back. */
    private fun json(s: Moovit.StopInfo): JSONObject {
        val o = JSONObject().put("id", s.id).put("name", s.name).put("code", s.code)
        if (s.lat.isFinite() && s.lon.isFinite()) o.put("lat", s.lat).put("lon", s.lon)
        return o
    }

    private fun stop(o: JSONObject) = Moovit.StopInfo(
        o.optInt("id"), o.optString("name"), o.optString("code"),
        o.optDouble("lat", Double.NaN), o.optDouble("lon", Double.NaN),
    )

    /** Paths are written flat, lat lon lat lon: half the brackets of a pair each. */
    private fun coords(path: List<Pair<Double, Double>>): JSONArray =
        JSONArray().apply { path.forEach { (lat, lon) -> put(lat); put(lon) } }

    private fun points(a: JSONArray?): List<Pair<Double, Double>> {
        if (a == null) return emptyList()
        val out = ArrayList<Pair<Double, Double>>(a.length() / 2)
        var i = 0
        while (i + 1 < a.length()) { out.add(a.optDouble(i) to a.optDouble(i + 1)); i += 2 }
        return out
    }

    private fun <V> keyed(o: JSONObject?, value: (JSONObject) -> V): Map<Int, V> {
        if (o == null) return emptyMap()
        val out = HashMap<Int, V>()
        for (k in o.keys()) out[k.toIntOrNull() ?: continue] = value(o.getJSONObject(k))
        return out
    }

    private fun counts(o: JSONObject?): Map<Int, Int> {
        if (o == null) return emptyMap()
        val out = HashMap<Int, Int>()
        for (k in o.keys()) out[k.toIntOrNull() ?: continue] = o.optInt(k)
        return out
    }

    private fun paths(o: JSONObject?): Map<Int, List<Pair<Double, Double>>> {
        if (o == null) return emptyMap()
        val out = HashMap<Int, List<Pair<Double, Double>>>()
        for (k in o.keys()) out[k.toIntOrNull() ?: continue] = points(o.optJSONArray(k))
        return out
    }

    private fun ints(a: JSONArray?): List<Int> =
        (0 until (a?.length() ?: 0)).map { a!!.optInt(it) }

    private fun strings(a: JSONArray?): List<String> =
        (0 until (a?.length() ?: 0)).map { a!!.optString(it) }

    private fun objects(a: JSONArray?): List<JSONObject> =
        (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
}
