package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uk.noammm.kav.data.RavPass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight

private class Month(val key: Int, val rides: List<RavPass.Ride>)

// year * 12 + month, from 0, in Israel time.
private fun monthKey(ms: Long): Int = Calendar.getInstance(ISRAEL).run { timeInMillis = ms; get(Calendar.YEAR) * 12 + get(Calendar.MONTH) }

private fun startOfMonth(ms: Long): Long = Calendar.getInstance(ISRAEL).run {
    timeInMillis = ms
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private fun monthName(key: Int): String = SimpleDateFormat("MMMM yyyy", if (T.rtl) Locale("iw") else Locale.US)
    .apply { timeZone = ISRAEL }
    .format(Calendar.getInstance(ISRAEL).apply { clear(); set(key / 12, key % 12, 1, 12, 0) }.time)

private fun shekels(agorot: Long): String = RavPass.Price(agorot).text

private fun RavPass.Ride.paid() = if (cancelled) 0L else amount?.agorot ?: 0L

// The payment history: totals at the top, a total for any dates, then every ride month by month. HopOn sends six
// months at a time, and the next six only when the list reaches them.
@Composable
fun PayHistoryScreen(onClose: () -> Unit, onTicket: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    val rides = remember { mutableStateListOf<RavPass.Ride>() }
    // The months asked for so far, counting back from this one, and whether HopOn has older ones.
    var offset by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableLongStateOf(startOfMonth(now)) }
    var to by remember { mutableLongStateOf(now) }
    var picking by remember { mutableStateOf(false) }

    suspend fun loadNext() {
        val (loaded, older) = Payer.call { RavPass.billings(it, offset) }
        val known = rides.map { it.id }.toSet()
        rides += loaded.filter { it.id !in known }
        offset += 6
        more = older
    }

    fun next() {
        if (loading || !more) return
        loading = true; error = null
        scope.launch {
            try { loadNext() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { next() }

    // The dates picked reach further back than the months loaded: the older ones come too.
    LaunchedEffect(from, offset, loading) {
        val oldest = Calendar.getInstance(ISRAEL).apply { timeInMillis = startOfMonth(now); add(Calendar.MONTH, -offset + 1) }.timeInMillis
        if (from < oldest && more && !loading && error == null) next()
    }

    val months = rides.groupBy { monthKey(it.boardedUtc) }.toSortedMap(compareByDescending { it })
        .map { (k, v) -> Month(k, v.sortedByDescending { it.boardedUtc }) }
    val thisMonth = months.firstOrNull { it.key == monthKey(now) } ?: Month(monthKey(now), emptyList())
    val lastMonth = months.firstOrNull { it.key == monthKey(now) - 1 }
    val inRange = rides.filter { it.boardedUtc in from..to }

    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, loading, more, rides.size) { if (nearEnd && !loading && more && error == null) next() }

    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Payment history", "היסטוריית תשלומים"), back = onClose)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = K.gap4, end = K.gap4, bottom = K.gap6 + LocalBottomBarInset.current),
        ) {
            item(key = "stats") { Stats(thisMonth.takeIf { offset > 0 }, lastMonth ?: Month(monthKey(now) - 1, emptyList()).takeIf { offset > 0 }) }
            item(key = "range") {
                Box(Modifier.padding(top = K.gap3).animateContentSize()) {
                    RangeCard(from, to, inRange.takeIf { offset > 0 }?.let { r -> r.sumOf { it.paid() } to r.count { !it.cancelled } }) { picking = true }
                }
            }
            // Months without rides are left out, but for this one.
            (listOf(thisMonth) + months.filter { it.key != thisMonth.key }).forEach { m ->
                item(key = "m${m.key}") { Column(Modifier.animateItem()) { MonthHeader(m) } }
                itemsIndexed(m.rides, key = { _, r -> "r${r.id}" }) { i, r ->
                    Box(Modifier.animateItem()) { RideRow(r, i == 0, i == m.rides.lastIndex, onTicket) }
                }
            }
            item(key = "end") {
                Box(Modifier.fillMaxWidth().padding(K.gap4), contentAlignment = Alignment.Center) {
                    when {
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                            Note(T("Couldn't load more.", "לא הצלחנו לטעון עוד."), color = K.critical)
                            KavButton(T("Try again", "נסו שוב"), { error = null; next() }, style = ButtonStyle.Secondary)
                        }
                        loading -> Note(T("Loading…", "טוען…"))
                        !more -> Note(T("That's the whole history.", "זו כל ההיסטוריה."))
                    }
                }
            }
        }
    }
    if (picking) RangeSheet(from, to, onDismiss = { picking = false }) { a, b -> from = a; to = b }
}

@Composable
private fun Stats(thisMonth: Month?, lastMonth: Month?) {
    fun paid(m: Month?) = m?.rides?.sumOf { it.paid() }
    val saved = thisMonth?.rides?.filter { !it.cancelled }?.sumOf { r ->
        val full = r.full?.agorot ?: 0L
        val amount = r.amount?.agorot ?: 0L
        if (full > amount) full - amount else 0L
    }
    Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        Text(T("This month", "החודש"), style = K.type.subhead)
        LtrText(paid(thisMonth)?.let { shekels(it) } ?: "…", 28.sp, K.text, FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
            StatLine(T("Rides", "נסיעות"), thisMonth?.rides?.count { !it.cancelled }?.toString() ?: "…")
            StatLine(T("Saved", "נחסך"), saved?.let { shekels(it) } ?: "…")
            StatLine(T("Last month", "חודש שעבר"), paid(lastMonth)?.let { shekels(it) } ?: "…")
        }
        Note(T("Rav-Pass charges the card once a month, with the Ministry of Transport's discounts and caps.",
            "רב-פס מחייבת את הכרטיס פעם בחודש, עם ההנחות ותקרות החיוב של משרד התחבורה."))
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Column {
        LtrText(value, 16.sp, K.text, FontWeight.Medium)
        Text(label, style = K.type.caption)
    }
}

private val dayFormat get() = SimpleDateFormat("d.M.yyyy", Locale.US).apply { timeZone = ISRAEL }

@Composable
private fun RangeCard(from: Long, to: Long, range: Pair<Long, Int>?, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().panel(K.rCard).clickable(role = Role.Button, onClick = onPick).padding(K.gap5),
        verticalArrangement = Arrangement.spacedBy(K.gap1),
    ) {
        Text(T("Any dates", "בין תאריכים"), style = K.type.subhead)
        LtrText(dayFormat.format(Date(from)) + " - " + dayFormat.format(Date(to)), 16.sp, K.accentInk)
        Text(
            range?.let { (total, count) -> T.ltr(shekels(total)) + if (count == 1) T(" in 1 ride", " בנסיעה אחת") else T(" in $count rides", " ב-$count נסיעות") } ?: "…",
            style = K.type.headline,
        )
    }
}

@Composable
private fun MonthHeader(m: Month) {
    Row(
        Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, top = K.gap6, bottom = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(monthName(m.key), style = K.type.caption, modifier = Modifier.weight(1f))
        Text(T.ltr(shekels(m.rides.sumOf { it.paid() })), style = K.type.caption.tabular)
    }
    if (m.rides.isEmpty()) Note(T("No rides yet this month.", "אין עדיין נסיעות החודש."), Modifier.padding(horizontal = K.gap4))
}

// One ride, and its ticket when it is one of today's that Kav still shows.
@Composable
private fun RideRow(r: RavPass.Ride, first: Boolean, last: Boolean, onTicket: (String) -> Unit) {
    val now = rememberNow()
    val ticket = Payer.tickets.firstOrNull { it.id == r.id }
    val time = SimpleDateFormat("d.M  $CLOCK", Locale.US).apply { timeZone = ISRAEL }
    Row(
        Modifier.fillMaxWidth().groupItem(first, last)
            .clickable(enabled = ticket != null, role = Role.Button) { ticket?.let { onTicket(it.id.toString()) } }
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f)) {
            val name = listOf(r.line.ifBlank { null }?.let { T("Line $it", "קו $it") }, r.towards.ifBlank { null })
                .filterNotNull().joinToString(" · ").ifBlank { T("Ride", "נסיעה") }
            Text(name + if (r.guests > 0) "  ×${r.guests + 1}" else "", style = K.type.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(T.ltr(time.format(Date(r.boardedUtc))) + "  " + when {
                r.cancelled -> T("Canceled", "בוטל")
                ticket != null -> ticketStatus(ticket, now)
                else -> r.from
            }, style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            LtrText(r.amount?.text ?: "", 16.sp, K.text)
            val full = r.full
            if (full != null && full.agorot > (r.amount?.agorot ?: 0L)) {
                LtrText(full.text, 12.sp, K.textTertiary, decoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            }
        }
    }
}

// Material's date pickers count in UTC midnights; the totals here are by Israel days.
private fun utcMidnight(ms: Long): Long {
    val il = Calendar.getInstance(ISRAEL).apply { timeInMillis = ms }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(il.get(Calendar.YEAR), il.get(Calendar.MONTH), il.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun israelDay(utcMidnight: Long, endOfDay: Boolean): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    val il = Calendar.getInstance(ISRAEL).apply {
        clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
        if (endOfDay) add(Calendar.DAY_OF_MONTH, 1)
    }
    return il.timeInMillis - if (endOfDay) 1 else 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSheet(from: Long, to: Long, onDismiss: () -> Unit, onPick: (Long, Long) -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = utcMidnight(from),
        initialSelectedEndDateMillis = utcMidnight(to),
    )
    BottomSheet(onDismiss, scrolls = true) { close ->
        DateRangePicker(
            state = state,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = K.surface1, titleContentColor = K.muted, headlineContentColor = K.text,
                weekdayContentColor = K.dim, subheadContentColor = K.muted, dayContentColor = K.text,
                selectedDayContainerColor = K.accent, selectedDayContentColor = K.onAccent,
                todayContentColor = K.accent, todayDateBorderColor = K.accent,
                dayInSelectionRangeContainerColor = K.plateStrong, dayInSelectionRangeContentColor = K.text,
            ),
        )
        Spacer(Modifier.height(K.gap3))
        val start = state.selectedStartDateMillis
        KavButton(T("Show", "הצגה"), {
            if (start != null) close {
                onPick(israelDay(start, false), israelDay(state.selectedEndDateMillis ?: start, true))
                onDismiss()
            }
        }, enabled = start != null, wide = true)
    }
}

// The rides of the last six months, kept while Kav runs so Home doesn't ask HopOn again on every visit.
private object SpendCache {
    var rides: List<RavPass.Ride>? = null
    var at = 0L
}

private fun shortMonth(key: Int): String =
    SimpleDateFormat("MMM", if (T.rtl) Locale("iw") else Locale.US).format(
        Calendar.getInstance(ISRAEL).apply { clear(); set(key / 12, key % 12, 1) }.time,
    )

// Home's look at paying: this month so far and its rides, the six months as bars with this one in the accent.
// A tap opens the history.
@Composable
internal fun SpendSummary(onOpen: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    var rides by remember { mutableStateOf(SpendCache.rides) }
    LaunchedEffect(Unit) {
        if (SpendCache.rides != null && now - SpendCache.at < 10 * 60_000L) return@LaunchedEffect
        runCatching { Payer.call { RavPass.billings(it, 0) }.first }.onSuccess {
            SpendCache.rides = it; SpendCache.at = now; rides = it
        }
    }
    val current = monthKey(now)
    val keys = (current - 5..current).toList()
    val byMonth = rides?.groupBy { monthKey(it.boardedUtc) }
    val spent = keys.map { k -> byMonth?.get(k)?.sumOf { it.paid() } ?: 0L }
    val count = byMonth?.get(current)?.count { !it.cancelled }
    val said = keys.zip(spent).joinToString(", ") { (k, v) -> shortMonth(k) + " " + shekels(v) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = T("Open payment history", "פתיחת היסטוריית התשלומים"), onClick = onOpen)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(T("This month", "החודש"), style = K.type.caption)
            LtrText(if (rides == null) "…" else shekels(spent.last()), 28.sp, K.text, FontWeight.SemiBold)
            Text(
                count?.let { T.count(it, "1 ride", "נסיעה אחת", "rides", "נסיעות") } ?: T("Loading…", "טוען…"),
                style = K.type.subhead,
            )
        }
        SpendBars(keys, spent, Modifier.semantics { contentDescription = T("Spent per month: ", "הוצאה לפי חודש: ") + said })
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
    }
}

// Six thin bars on one baseline, rounded at the top, 2 dp apart; the month's initials under them in text colours.
@Composable
private fun SpendBars(keys: List<Int>, spent: List<Long>, modifier: Modifier = Modifier) {
    val top = spent.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val past = K.textTertiary
    val now = K.accentInk
    val floor = K.fillStrong
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 108.dp, height = 44.dp)) {
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (keys.size - 1)) / keys.size
            val r = minOf(w / 2, 4.dp.toPx())
            keys.indices.forEach { i ->
                val x = mirrorX((i * (w + gap) + w / 2) / size.width) * size.width - w / 2
                val h = (spent[i].toFloat() / top) * size.height
                if (h < 1f) {
                    drawRoundRect(floor, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                } else {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(
                            x, size.height - h, x + w, size.height,
                            topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                        ))
                    }
                    drawPath(path, if (i == keys.lastIndex) now else past)
                }
            }
        }
        Spacer(Modifier.height(K.gap1))
        Row(Modifier.width(108.dp)) {
            keys.forEachIndexed { i, k ->
                Text(
                    shortMonth(k).take(1), style = K.type.caption, color = if (i == keys.lastIndex) K.text else K.textTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
