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
    for (i in name.indices) {
        val h = hay[i]
        var ok = true
        var sc = 0.0
        for (t in need) {
            val at = h.indexOf(t)
            if (at < 0) { ok = false; break }
            sc += if (at == 0) 0.0 else minOf(at, 40) / 100.0
        }
        if (ok) { idx.add(i); score.add(sc); continue }
        if (need.size == 1 && code[i].toString().startsWith(need[0])) { idx.add(i); score.add(0.5) }
    }
    val orderIdx = idx.indices.sortedBy { score[it] }
    return IntArray(minOf(limit, orderIdx.size)) { idx[orderIdx[it]] }
}

/** Routes whose short or long name contains every typed word.
 *  Uncapped on purpose: the national feed has 7,730 routes and a silent
 *  top-N would read as "these are all the lines" when it is 5% of them.
 *  A LazyColumn only composes what is on screen, so the full list is cheap. */
fun Net.searchRoutes(q: String, mode: Int = -1): IntArray {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = ArrayList<Int>(256)
    // The MOT feed emits one GTFS route row per service pattern, so a single
    // rail direction appears ~500 times under the same name. Rows that are
    // identical in both names ARE the same line; collapse them. Opposite
    // directions differ in route_long_name and are deliberately kept apart.
    val seen = HashSet<String>(1024)
    for (r in rShort.indices) {
        if (mode >= 0 && rType[r] != mode) continue
        if (need.isNotEmpty()) {
            if (need.size == 1 && need[0].toIntOrNull() != null) {
                if (!rShort[r].startsWith(need[0]) ) continue
            }
            val h = (rShort[r] + " " + rLong[r]).lowercase()
            if (!need.all { h.contains(it) }) continue
        }
        if (!seen.add(rShort[r] + "\u0000" + rLong[r])) continue
        out.add(r)
    }
    out.sortWith { i, i1 -> rShort[i].compareTo(rShort[i1])  }
    return out.toIntArray()
}

/** The longest trip on a route, the best single stand-in for "the line". */
fun Net.representativeTrip(route: Int): Int {
    var best = -1; var bestLen = -1
    for (t in tripRoute.indices) {
        if (tripRoute[t] != route) continue
        val len = tripStart[t + 1] - tripStart[t]
        if (len > bestLen) { bestLen = len; best = t }
    }
    return best
}

/** Stops within [radius] metres, nearest first. */
fun Net.nearestStops(la: Double, lo: Double, k: Int = 24, radius: Double = 2500.0): List<Pair<Int, Double>> {
    val out = ArrayList<Pair<Int, Double>>(k * 4)
    for (i in lat.indices) {
        // cheap box reject before the trig
        if (kotlin.math.abs(lat[i] - la) > 0.03 || kotlin.math.abs(lon[i] - lo) > 0.04) continue
        val d = uk.noammm.kav.ui.metres(la, lo, lat[i], lon[i])
        if (d < radius) out.add(i to d)
    }
    return out.sortedBy { it.second }.take(k)
}
