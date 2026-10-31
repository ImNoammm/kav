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

// A line's departures from one stop that go on to reach another, soonest first from second now of the board's day
// today (Sunday 0) and within the next four hours, at most limit: the stop time each leaves from and when. from and to are every stop with the codes
// the ride is boarded and left at, as a station's platforms share one. number: the line's; null for any train, each of
// whose runs is a route of its own.
fun Net.lineDepartures(from: Set<Int>, to: Set<Int>, number: String?, now: Int, today: Int, limit: Int = 4): List<Pair<Int, Int>> =
    from.flatMap { departuresAt(it, now, today, limit = 240) }
        .map { (c, dep) -> cST[c] to dep }
        .filter { (st, _) ->
            val t = tripOf(st)
            val route = tripRoute[t]
            (if (number == null) rType[route] == 2 else rShort[route] == number) &&
                (st + 1 until tripStart[t + 1]).any { stStop[it] in to }
        }
        .filter { (_, dep) -> dep - now <= 4 * 3600 }
        .distinctBy { (st, dep) -> tripOf(st) to dep }
        .sortedBy { it.second }
        .take(limit)

// A train on a station's board as a live source names it: the timetable trip that is its row, and where it really
// ends when that's a station before the timetable's last, as when works end a night's trains early.
class TrainRow(val trip: Int, val to: String?)

// Today's train leaving the stop at about second (from the board's midnight, as its rows count) that a live source
// names by where it comes from and goes, starts(name) and ends(name) saying whether a station's name is that place.
// Of the trains leaving within a minute: the one from and to there, else the one ending there, else one calling there
// later on, else the only one. Works can move a night's train by a few minutes, so failing those, the nearest within
// five that ends or calls there. Null when none fits or it can't tell which.
fun Net.trainLeaving(stop: Int, second: Int, today: Int, starts: (String) -> Boolean, ends: (String) -> Boolean): TrainRow? {
    val near = departuresAt(stop, second - MOVED_S, today).map { (c, dep) -> cST[c] to dep }
        .filter { (st, dep) -> dep <= second + MOVED_S && rType[tripRoute[tripOf(st)]] == 2 }
        .sortedBy { (_, dep) -> kotlin.math.abs(dep - second) }
    val close = near.filter { (_, dep) -> kotlin.math.abs(dep - second) <= 60 }
    fun first(st: Int) = name[stStop[tripStart[tripOf(st)]]]
    fun last(st: Int) = name[tripLast(tripOf(st))]
    (close.firstOrNull { (st, _) -> starts(first(st)) && ends(last(st)) } ?: close.firstOrNull { (st, _) -> ends(last(st)) })
        ?.let { (st, _) -> return TrainRow(tripOf(st), null) }
    for ((st, _) in close) cutShort(st, ends)?.let { return TrainRow(tripOf(st), it) }
    close.singleOrNull()?.let { (st, _) -> return TrainRow(tripOf(st), null) }
    for ((st, _) in near) {
        if (ends(last(st))) return TrainRow(tripOf(st), null)
        cutShort(st, ends)?.let { return TrainRow(tripOf(st), it) }
    }
    return null
}

// How far works can move a train from its timetabled time and it still be told for the same one.
const val MOVED_S = 5 * 60

// Where a train calling at stop time st really ends when a live source says it goes somewhere other than the
// timetable trip's end: the first station after st that ends(name) says is it. Null when the trip ends there anyway or
// calls nowhere that fits.
fun Net.cutShort(st: Int, ends: (String) -> Boolean): String? {
    val t = tripOf(st)
    if (ends(name[tripLast(t)])) return null
    return (st + 1 until tripStart[t + 1]).firstOrNull { ends(name[stStop[it]]) }?.let { name[stStop[it]] }
}

// What a live source says of a station's trains today: the timetable's trains it has running, where those end early,
// and which it has cancelled, until when (seconds from the board's midnight) it would list any other,
// -1 when it can't say, and around when it lists trains that fit no row, where nothing is said. named: from Kav's
// server, the number and platform of each train not yet out, which the timetable keeps without either.
class TrainsToday(
    val running: Set<Int> = emptySet(), val cancelled: Set<Int> = emptySet(), val until: Int = -1,
    val unsure: List<IntRange> = emptyList(), val ends: Map<Int, String> = emptyMap(),
    val named: Map<Int, TrainName> = emptyMap(),
) {
    // A train row it has cancelled, or would list but doesn't: not running today, as works or a holiday can have it.
    fun missing(net: Net, trip: Int, dep: Int) = net.rType[net.tripRoute[trip]] == 2 && trip !in running &&
        (trip in cancelled || dep <= until && unsure.none { dep in it })
}

// A train's number and the platform it leaves a station from. Blank where the live
// source hasn't given one.
class TrainName(val number: String, val platform: String) {
    companion object {
        // A live row's, when it names the train by its number, as Kav's server does.
        fun of(number: String, platform: String) = TrainName(number, platform).takeIf { number.isNotEmpty() && number.all(Char::isDigit) }
    }
}

// A train's line as people mean it: every train the timetable runs between the same two stations the same way, each
// of which it keeps as a route of its own.
fun Net.trainLine(route: Int): Set<Int> =
    (0 until nRoutes).filterTo(HashSet()) {
        rType[it] == 2 && rShort[it] == rShort[route] && rLong[it] == rLong[route] && rAgency[it] == rAgency[route]
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
