package uk.noammm.kav.data

// Trips past midnight are written as 24:00 and later, so last night's run of one can still be to come.
// Tomorrow's trips follow today's, so a board opened late at night isn't empty.
fun Net.departuresAt(stop: Int, now: Int, today: Int, limit: Int = 60): List<Pair<Int, Int>> {
    val yesterday = (today + 6) % 7
    val tomorrow = (today + 1) % 7
    val out = ArrayList<Pair<Int, Int>>()
    var i = dStart[stop]
    while (i < dStart[stop + 1]) {
        val c = dConn[i]
        val st = cST[c]
        val dep = stDep[st]
        val t = tripOf(st)
        if (dep >= now && runsOn(t, today)) out.add(c to dep)
        if (dep - 86_400 >= now && runsOn(t, yesterday)) out.add(c to dep - 86_400)
        if (dep < 86_400 && runsOn(t, tomorrow)) out.add(c to dep + 86_400)
        i++
    }
    return out.sortedBy { it.second }.take(limit)
}

// When one line leaves a stop today, in order: today's trips, and yesterday's still running after midnight.
fun Net.lineTimesAt(stop: Int, route: Int, today: Int): List<Int> {
    val yesterday = (today + 6) % 7
    val out = ArrayList<Int>()
    for (i in dStart[stop] until dStart[stop + 1]) {
        val st = cST[dConn[i]]
        val t = tripOf(st)
        if (tripRoute[t] != route) continue
        val dep = stDep[st]
        if (runsOn(t, today)) out.add(dep)
        if (dep >= 86_400 && runsOn(t, yesterday)) out.add(dep - 86_400)
    }
    return out.distinct().sorted()
}
