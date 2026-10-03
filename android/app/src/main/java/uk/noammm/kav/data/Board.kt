package uk.noammm.kav.data

// The departures still to come from a stop today, soonest first, as (connection, seconds
// from today's midnight). `today` is the day of the week, 0 for Sunday.
//
// Trips past midnight are written as 24:00 and later, on the day they set out. So a trip
// written 24:10 can leave twice in the small hours: last night's run, ten minutes after
// midnight today, and tonight's, a day later. Both count.
fun Net.departuresAt(stop: Int, now: Int, today: Int, limit: Int = 60): List<Pair<Int, Int>> {
    val yesterday = (today + 6) % 7
    val out = ArrayList<Pair<Int, Int>>()
    var i = dStart[stop]
    while (i < dStart[stop + 1]) {
        val c = dConn[i]
        val st = cST[c]
        val dep = stDep[st]
        val t = tripOf(st)
        if (dep >= now && runsOn(t, today)) out.add(c to dep)
        if (dep - 86_400 >= now && runsOn(t, yesterday)) out.add(c to dep - 86_400)
        i++
    }
    return out.sortedBy { it.second }.take(limit)
}
