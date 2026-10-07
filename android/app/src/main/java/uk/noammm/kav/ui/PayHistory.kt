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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uk.noammm.kav.data.MoovitPay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private class Month(val key: Int, val charges: List<MoovitPay.Charge>)

internal fun currentChargeTicket(c: MoovitPay.Charge, wallet: MoovitPay.Wallet?): MoovitPay.Ticket? {
    val near = wallet?.tickets.orEmpty().filter { kotlin.math.abs(it.boughtUtc - c.atUtc) < 30_000 }
    val priced = near.filter { c.amount != null && it.price?.agorot == c.amount.agorot }
    return priced.ifEmpty { near }.minByOrNull { kotlin.math.abs(it.boughtUtc - c.atUtc) }
}

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

private fun shekels(agorot: Long): String = MoovitPay.Price(agorot, "ILS").text

// The payment history: totals at the top, a total for any dates, then every charge month by month. A month
// is asked for only when the list reaches it, so a long history never arrives all at once.
@Composable
fun PayHistoryScreen(onClose: () -> Unit, onTicket: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    val statusNow = rememberNow()
    val months = remember { mutableStateListOf<Month>() }
    val cache = remember { HashMap<Int, List<MoovitPay.Charge>>() }
    var billing by remember { mutableStateOf<MoovitPay.Billing?>(null) }
    var cursor by remember { mutableIntStateOf(monthKey(now)) }
    var empty by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableLongStateOf(startOfMonth(now)) }
    var to by remember { mutableLongStateOf(now) }
    var range by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var picking by remember { mutableStateOf(false) }

    suspend fun monthOf(key: Int): List<MoovitPay.Charge> =
        cache[key] ?: Payer.call { MoovitPay.history(it, key % 12 + 1, key / 12) }.also { cache[key] = it }

    // A year of empty months in a row is taken as the start of the history.
    val done = empty >= 12

    suspend fun loadNext() {
        val key = cursor
        val list = monthOf(key)
        months += Month(key, list)
        empty = if (list.isEmpty()) empty + 1 else 0
        cursor = key - 1
    }

    fun more() {
        if (loading || done) return
        loading = true; error = null
        scope.launch {
            try { loadNext() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        try {
            billing = Payer.call { MoovitPay.billing(it) }
            loadNext()
            loadNext()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
        loading = false
    }

    // History can be opened directly after an app restart, before Home has loaded the tickets.
    LaunchedEffect(Unit) { runCatching { Payer.refresh() } }

    LaunchedEffect(from, to) {
        range = null
        try {
            var total = 0L
            var count = 0
            // Newest month first, never one still to come; a year of empty months in a row is the start of the
            // history, as in the list below.
            var empties = 0
            for (key in minOf(monthKey(to), monthKey(now)) downTo monthKey(from)) {
                val charges = monthOf(key)
                empties = if (charges.isEmpty()) empties + 1 else 0
                if (empties >= 12) break
                charges.filter { it.atUtc in from..to }.forEach { total += it.amount?.agorot ?: 0L; count++ }
            }
            range = total to count
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure(e) }
    }

    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, loading, done, months.size) { if (nearEnd && !loading && !done && error == null) more() }

    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Payment", "היסטוריית"), T("history", "תשלומים"), back = onClose)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = K.gap4, end = K.gap4, bottom = K.gap6 + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            item(key = "stats") { Stats(billing, months.firstOrNull { it.key == monthKey(now) }, months.firstOrNull { it.key == monthKey(now) - 1 }) }
            item(key = "range") { Box(Modifier.animateContentSize()) { RangeCard(from, to, range) { picking = true } } }
            // Months without rides are left out, but for this one.
            months.filter { it.charges.isNotEmpty() || it.key == monthKey(now) }.forEach { m ->
                item(key = "m${m.key}") { Column(Modifier.animateItem()) { MonthHeader(m) } }
                itemsIndexed(m.charges, key = { i, _ -> "c${m.key}_$i" }) { _, c ->
                    Box(Modifier.animateItem()) { ChargeRow(c, statusNow, onTicket) }
                }
            }
            item(key = "end") {
                Box(Modifier.fillMaxWidth().padding(K.gap4), contentAlignment = Alignment.Center) {
                    when {
                        error != null -> Text(T("Couldn't load more. Tap to try again.", "לא הצלחנו לטעון עוד. הקישו לנסות שוב."),
                            fontSize = 14.sp, color = K.critical,
                            modifier = Modifier.clickable(role = Role.Button) { error = null; more() })
                        loading -> Note(T("Loading…", "טוען…"))
                        done -> Note(T("That's the whole history.", "זו כל ההיסטוריה."))
                    }
                }
            }
        }
    }
    if (picking) RangeSheet(from, to, onDismiss = { picking = false }) { a, b -> from = a; to = b }
}

@Composable
private fun Stats(billing: MoovitPay.Billing?, thisMonth: Month?, lastMonth: Month?) {
    fun paid(m: Month?) = m?.charges?.sumOf { it.amount?.agorot ?: 0L }
    val saved = thisMonth?.charges?.sumOf { c ->
        val full = c.full?.agorot ?: 0L
        val amount = c.amount?.agorot ?: 0L
        if (full > amount) full - amount else 0L
    }
    Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        Text(T("This month", "החודש"), fontSize = 14.sp, color = K.muted)
        LtrText(paid(thisMonth)?.let { shekels(it) } ?: "…", 28.sp, K.text, FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
            StatLine(T("Rides", "נסיעות"), thisMonth?.charges?.size?.toString() ?: "…")
            StatLine(T("Saved", "נחסך"), saved?.let { shekels(it) } ?: "…")
            StatLine(T("Last month", "חודש שעבר"), paid(lastMonth)?.let { shekels(it) } ?: "…")
        }
        billing?.current?.price?.let {
            Note(T("Open bill so far: ", "חשבון פתוח עד כה: ") + T.ltr(it.text))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Column {
        LtrText(value, 16.sp, K.text, FontWeight.Medium)
        Text(label, fontSize = 12.sp, color = K.dim)
    }
}

private val dayFormat get() = SimpleDateFormat("d.M.yyyy", Locale.US).apply { timeZone = ISRAEL }

@Composable
private fun RangeCard(from: Long, to: Long, range: Pair<Long, Int>?, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().panel(K.rCard).clickable(role = Role.Button, onClick = onPick).padding(K.gap5),
        verticalArrangement = Arrangement.spacedBy(K.gap1),
    ) {
        Text(T("Any dates", "בין תאריכים"), fontSize = 14.sp, color = K.muted)
        LtrText(dayFormat.format(Date(from)) + " - " + dayFormat.format(Date(to)), 16.sp, K.accent)
        Text(
            range?.let { (total, count) -> T.ltr(shekels(total)) + T(" in $count rides", " ב-$count נסיעות") } ?: "…",
            fontSize = 18.sp, color = K.text, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MonthHeader(m: Month) {
    Row(Modifier.fillMaxWidth().padding(top = K.gap3), verticalAlignment = Alignment.CenterVertically) {
        Text(monthName(m.key), fontSize = 15.sp, color = K.muted, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        LtrText(shekels(m.charges.sumOf { it.amount?.agorot ?: 0L }), 14.sp, K.dim)
    }
    if (m.charges.isEmpty()) Note(T("No rides yet this month.", "אין עדיין נסיעות החודש."))
}

@Composable
private fun ChargeRow(c: MoovitPay.Charge, now: Long, onTicket: (String) -> Unit) {
    // Use the same status as Home, including when the free-transfer deadline passes while history is open.
    val wallet = Payer.wallet
    val ticket = currentChargeTicket(c, wallet)
    val cancelled = ticket?.let { Payer.cancelled(it) } ?: Payer.cancelledAt(c.atUtc)
    val time = SimpleDateFormat("d.M  $CLOCK", Locale.US).apply { timeZone = ISRAEL }
    Row(
        Modifier.fillMaxWidth().panel(K.rControl)
            .clickable(enabled = ticket != null, role = Role.Button) { ticket?.let { onTicket(Payer.rideKey(it)) } }
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name.ifBlank { T("Ride", "נסיעה") }, fontSize = 15.sp, color = K.text)
            Text(T.ltr(time.format(Date(c.atUtc))) + "  " + when {
                cancelled -> T("Canceled", "בוטל")
                ticket != null -> ticketStatus(ticket, wallet?.window, now).text
                wallet == null -> T("Checking…", "בודק…")
                else -> T("Inactive ticket", "כרטיס לא פעיל")
            },
                fontSize = 13.sp, color = K.dim)
        }
        Column(horizontalAlignment = Alignment.End) {
            LtrText(c.amount?.text ?: "", 15.sp, K.text)
            val full = c.full
            if (full != null && full.agorot > (c.amount?.agorot ?: 0L)) {
                LtrText(full.text, 12.sp, K.dim, decoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
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
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).panel(K.rPill)
                .clickable(enabled = start != null, role = Role.Button) {
                    if (start != null) close {
                        onPick(israelDay(start, false), israelDay(state.selectedEndDateMillis ?: start, true))
                        onDismiss()
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text(T("Show", "הצגה"), fontSize = 15.sp, color = if (start != null) K.accent else K.dim) }
    }
}
