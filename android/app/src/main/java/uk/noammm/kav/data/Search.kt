package uk.noammm.kav.data

/**
 * Multi-token stop search over "<name> <city>".
 *
 * The Israeli MOT feed hides the city in `stop_desc`, so without the indexed city
 * table a query like "הדרור 30 ראש העין" can never match. Every word must appear
 * somewhere in the haystack, in any order. Bare numbers are house numbers rather
 * than stop names, so they are dropped, unless nothing else was typed, in which
 * case they are tried against the stop code.
 */
fun Net.searchStops(q: String, limit: Int = 60): IntArray {
    val raw = q.trim().lowercase()
    if (raw.isEmpty()) return IntArray(0)
    val all = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val words = all.filter { !it.all { c -> c.isDigit() } }
    val need = words.ifEmpty { all }

    val idx = ArrayList<Int>(limit * 4)
    val score = ArrayList<Double>(limit * 4)
    for (i in stops.indices) {
        val h = hay[i]
        var ok = true
        var sc = 0.0
        for (t in need) {
            val at = h.indexOf(t)
            if (at < 0) { ok = false; break }
            sc += if (at == 0) 0.0 else minOf(at, 40) / 100.0
        }
        if (ok) { idx.add(i); score.add(sc); continue }
        if (need.size == 1 && stops[i].code.toString().startsWith(need[0])) { idx.add(i); score.add(0.5) }
    }
    val orderIdx = idx.indices.sortedBy { score[it] }
    return IntArray(minOf(limit, orderIdx.size)) { idx[orderIdx[it]] }
}

/** Routes whose short or long name contains every typed word.
 *  Uncapped on purpose: the national feed has 7,730 routes and a silent
 *  top-N would read as "these are all the lines" when it is 5% of them.
 *  A LazyColumn only composes what is on screen, so the full list is cheap. */
fun Net.searchRoutes(q: String, mode: Int = -1, here: Pair<Double, Double>? = null): IntArray {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = ArrayList<Int>(256)
    // The MOT feed emits one GTFS route row per service pattern, so a single
    // rail direction appears ~500 times under the same name; rows identical in
    // both names ARE the same line, collapse them. Opposite directions differ in
    // route_long_name instead, but Route.directions already paired those up at
    // load time, so the lower-indexed one represents both on screen (the Lines
    // screen offers a direction switcher rather than listing both rows).
    val seen = HashSet<String>(1024)
    val added = BooleanArray(nRoutes)
    for (r in routes.indices) {
        val route = routes[r]
        if (mode >= 0 && route.type != mode) continue
        if (need.isNotEmpty()) {
            if (need.size == 1 && need[0].toIntOrNull() != null) {
                if (!route.short.startsWith(need[0]) ) continue
            }
            val h = (route.short + " " + route.long).lowercase()
            if (!need.all { h.contains(it) }) continue
        }
        val partner = if (route.directions.size > 1) route.directions[1] else -1
        if (partner in 0 until r && added[partner]) continue
        if (!seen.add(route.short + "|" + route.long)) continue
        out.add(r); added[r] = true
    }
    // Nearest-endpoint distance to `here`, only computed when a location is given;
    // it breaks ties among same-numbered lines from different towns.
    fun distance(r: Int): Double {
        if (here == null) return 0.0
        val s = routes[r].stops[0]
        if (s.isEmpty()) return Double.MAX_VALUE
        val a = stops[s.first()]; val b = stops[s.last()]
        return minOf(
            uk.noammm.kav.ui.metres(here.first, here.second, a.lat, a.lon),
            uk.noammm.kav.ui.metres(here.first, here.second, b.lat, b.lon),
        )
    }
    out.sortWith(compareBy({ routes[it].short }, { distance(it) }))
    return out.toIntArray()
}

/** Stops within [radius] metres, nearest first. */
fun Net.nearestStops(la: Double, lo: Double, k: Int = 24, radius: Double = 2500.0): List<Pair<Int, Double>> {
    val out = ArrayList<Pair<Int, Double>>(k * 4)
    for (i in stops.indices) {
        val s = stops[i]
        // cheap box reject before the trig
        if (kotlin.math.abs(s.lat - la) > 0.03 || kotlin.math.abs(s.lon - lo) > 0.04) continue
        val d = uk.noammm.kav.ui.metres(la, lo, s.lat, s.lon)
        if (d < radius) out.add(i to d)
    }
    return out.sortedBy { it.second }.take(k)
}
