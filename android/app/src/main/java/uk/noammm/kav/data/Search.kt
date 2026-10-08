package uk.noammm.kav.data

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

internal fun searchWords(s: String): List<String> {
    val sb = StringBuilder(s.length)
    for (c in java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)) {
        if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.COMBINING_DIACRITICAL_MARKS) continue
        sb.append(if (c.isLetter()) c.lowercaseChar() else if (c.isDigit()) c else ' ')
    }
    return sb.split(' ').filter { it.isNotEmpty() }
}

internal fun spacedWords(s: String) = searchWords(s).joinToString(" ", " ", " ")

// Words that just say "stop" or "station": stop names rarely carry them, so they'd sink every match.
private val generic = setOf("תחנת", "תחנה", "תחנות", "station", "stop")

// Like Moovit's own stop search: every typed word has to start a word of the name, code or mode.
fun Net.stopsMatching(q: String, at: Pair<Double, Double>?, modeName: (Int) -> String): List<Int> {
    val typed = searchWords(q)
    val need = typed.filter { it !in generic }.ifEmpty { typed }.map { " $it" }
    if (need.isEmpty()) return emptyList()
    val words = stopWords
    val types = stopType
    val modes = HashMap<Int, String>()
    val codes = HashSet<Int>()
    val hits = ArrayList<Int>()
    for (i in words.indices) {
        val mode = modes.getOrPut(types[i]) { if (types[i] < 0) "" else spacedWords(modeName(types[i])) }
        if (need.all { words[i].contains(it) || mode.contains(it) } && (code[i] <= 0 || codes.add(code[i]))) hits.add(i)
    }
    if (at == null) return hits.sortedBy { name[it] }.take(4)
    val (la, lo) = at
    fun sq(i: Int) = (la - lat[i]) * (la - lat[i]) + (lo - lon[i]) * (lo - lon[i])
    return hits.sortedWith(compareBy({ sq(it) }, { name[it] })).take(4)
        .sortedWith(compareBy({ Math.round(uk.noammm.kav.ui.metres(la, lo, lat[it], lon[it])) }, { name[it] }))
}

fun Net.searchRoutes(q: String, types: IntArray = intArrayOf()): IntArray {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = ArrayList<Int>(256)
    val seen = HashSet<String>(1024)
    for (r in rShort.indices) {
        if (types.isNotEmpty() && rType[r] !in types) continue
        if (need.isNotEmpty()) {
            val h = (rShort[r] + " " + rLong[r]).lowercase()
            if (!need.all { h.contains(it) }) continue
        }
        if (!seen.add(rShort[r] + "\u0000" + rLong[r])) continue
        out.add(r)
    }
    return out.toIntArray()
}

fun Net.representativeTrip(route: Int): Int {
    var best = -1; var bestLen = -1
    for (t in tripRoute.indices) {
        if (tripRoute[t] != route) continue
        val len = tripStart[t + 1] - tripStart[t]
        if (len > bestLen) { bestLen = len; best = t }
    }
    return best
}

fun Net.nearestStops(la: Double, lo: Double, k: Int = 24, radius: Double = 2500.0): List<Pair<Int, Double>> {
    val out = ArrayList<Pair<Int, Double>>(k * 4)
    for (i in lat.indices) {
        if (kotlin.math.abs(lat[i] - la) > 0.03 || kotlin.math.abs(lon[i] - lo) > 0.04) continue
        val d = uk.noammm.kav.ui.metres(la, lo, lat[i], lon[i])
        if (d < radius) out.add(i to d)
    }
    return out.sortedBy { it.second }.take(k)
}
