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

// Whether Moovit's name for a train's line, "from - to", starts or ends at the station of this name. A station's name
// can hold " - " itself ("בית שאן - דוד לוי"). Moovit adds symbols, like the airport's plane; both write gershayim as
// two apostrophes.
fun moovitLineStartsAt(line: String, station: String): Boolean {
    val l = stationKey(line)
    val s = stationKey(station)
    return s.isNotEmpty() && (l == s || l.startsWith("$s - "))
}

fun moovitLineEndsAt(line: String, station: String): Boolean {
    val l = stationKey(line)
    val s = stationKey(station)
    return s.isNotEmpty() && (l == s || l.endsWith(" - $s"))
}

private fun stationKey(name: String): String = name.replace("״", "''").replace("\"", "''").replace("׳", "'")
    .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '\'' }
    .replace(Regex("\\s+"), " ").trim()

// What a live source says of a station's trains today: the timetable's trains it has running, where those end early,
// and from Moovit, which it has cancelled, until when (seconds from the board's midnight) it would list any other,
// -1 when it can't say, and around when it lists trains that fit no row, where nothing is said.
class TrainsToday(
    val running: Set<Int> = emptySet(), val cancelled: Set<Int> = emptySet(), val until: Int = -1,
    val unsure: List<IntRange> = emptyList(), val ends: Map<Int, String> = emptyMap(),
) {
    // A train row it has cancelled, or would list but doesn't: not running today, as works or a holiday can have it.
    fun missing(net: Net, trip: Int, dep: Int) = net.rType[net.tripRoute[trip]] == 2 && trip !in running &&
        (trip in cancelled || dep <= until && unsure.none { dep in it })
}

// A train as Moovit lists it at a station: when it's timetabled there and expected (seconds from the board's
// midnight, rt -1 without a live time), its line's name and whether Moovit has cancelled it.
class MoovitTrain(val second: Int, val rt: Int, val line: String, val cancelled: Boolean) {
    fun toCome(now: Int) = (if (rt >= 0) rt else second) >= now - 60
}

// Moovit's trains at a station on the board's rows: the row of each, null where none fits, and what the list says of
// the rest. Moovit's list is its timetable for today, works and all, so a train row it doesn't have in the next hour
// and a half isn't running today, unless a train Moovit has can't be placed within a quarter of an hour of it.
fun Net.moovitTrains(stop: Int, today: Int, now: Int, trains: List<MoovitTrain>): Pair<List<TrainRow?>, TrainsToday> {
    val rows = trains.map { m ->
        trainLeaving(stop, m.second, today, { moovitLineStartsAt(m.line, it) }, { moovitLineEndsAt(m.line, it) })
    }
    val running = HashSet<Int>()
    val cancelled = HashSet<Int>()
    val unsure = ArrayList<IntRange>()
    val ends = HashMap<Int, String>()
    trains.forEachIndexed { i, m ->
        val row = rows[i]
        when {
            row == null -> if (!m.cancelled && m.toCome(now)) unsure += m.second - 900..m.second + 900
            m.cancelled -> cancelled += row.trip
            else -> { running += row.trip; row.to?.let { ends[row.trip] = it } }
        }
    }
    val until = if (running.isNotEmpty()) now + LISTED_S else -1
    return rows to TrainsToday(running, cancelled - running, until, unsure, ends)
}

// How far ahead Moovit's list is trusted to hold every train.
const val LISTED_S = 85 * 60

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
