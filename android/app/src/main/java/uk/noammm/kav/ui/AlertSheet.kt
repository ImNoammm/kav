@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.LineAlert
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.stopKey
import uk.noammm.kav.data.shownName
import java.util.Locale
import kotlin.math.abs

// Line alerts at a stop: the ones set there, and a new one's lines, days and hours. Every choice is a tap: lines and
// days are toggles that fill when on, and the hours turn on wheels under the time being set, no keyboard.
@Composable
internal fun AlertSheet(model: KavModel, net: Net, stop: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val key = net.stopKey(stop)
    val numbers = remember(stop) { net.linesAt(stop) }
    // A train station numbers no line: its trains are chosen by where they end.
    val ends = remember(stop) { if (numbers.isEmpty()) net.endsAt(stop) else emptyList() }
    var lines by remember(stop) { mutableStateOf(emptySet<String>()) }
    var toward by remember(stop) { mutableStateOf(emptySet<Int>()) }
    var days by remember { mutableIntStateOf(LineAlert.WEEKDAYS) }
    var from by remember { mutableIntStateOf(8 * 60) }
    var to by remember { mutableIntStateOf(9 * 60) }
    // The time on the wheels: 0 from, 1 until, null neither. last keeps it while the wheels fold away.
    var editing by remember { mutableStateOf<Int?>(null) }
    var last by remember { mutableIntStateOf(0) }
    fun edit(which: Int) {
        if (editing == which) editing = null else { editing = which; last = which }
    }
    // Let post notifications, the alarm that was left off for them is set.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) uk.noammm.kav.LineAlerts.reschedule(ctx)
    }
    BottomSheet(onDismiss, scrolls = true) { close ->
        SheetHeader(T("Line alerts", "התראות קווים"), { close(onDismiss) }, subtitle = net.stopName(stop))
        val set = model.lineAlerts.filter { it.stop == key }
        if (set.isNotEmpty()) {
            Spacer(Modifier.height(K.gap3))
            ListGroup {
                set.forEach { a ->
                    row {
                        ListRow(
                            alertWhat(a), subtitle = alertWhen(a), chevron = false,
                            trailing = {
                                KavIconButton(
                                    Icons.Rounded.DeleteOutline, T("Delete this alert", "מחיקת ההתראה"),
                                    { model.saveAlerts(ctx, model.lineAlerts.filter { it.id != a.id }) }, size = 40.dp,
                                )
                            },
                        )
                    }
                }
            }
        }
        if (numbers.isEmpty() && ends.isEmpty()) {
            Spacer(Modifier.height(K.gap3))
            NoteCard(T("Nothing leaves from here to be alerted of.", "מכאן לא יוצא דבר שאפשר לקבל עליו התראה."))
        } else {
            if (numbers.isEmpty()) {
                Heading(T("Trains to", "רכבות אל"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    ends.forEach { e ->
                        KavChip(net.stopName(e), e in toward, { toward = if (e in toward) toward - e else toward + e }, check = true)
                    }
                }
            } else Heading(T("Lines", "קווים"))
            if (numbers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                numbers.forEach { n ->
                    KavChip(n, n in lines, { lines = if (n in lines) lines - n else lines + n }, Modifier.semantics { contentDescription = T("Line $n", "קו $n") }, check = true)
                }
            }
            Heading(T("Days", "ימים"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 0..6) DayToggle(dayName(d), days and (1 shl d) != 0, Modifier.weight(1f)) { days = days xor (1 shl d) }
            }
            Heading(T("Hours", "שעות"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                TimeButton(T("From", "משעה"), from, editing == 0, Modifier.weight(1f)) { edit(0) }
                TimeButton(T("Until", "עד שעה"), to, editing == 1, Modifier.weight(1f)) { edit(1) }
            }
            AnimatedVisibility(editing != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                key(last) {
                    // Moving the start carries the end along, so the window keeps its length, as a calendar does.
                    TimeWheels(if (last == 0) from else to) { m ->
                        if (last == 0) { to = Math.floorMod(to + m - from, 1440); from = m } else to = m
                    }
                }
            }
            Spacer(Modifier.height(K.gap5))
            val ready = (lines.isNotEmpty() || toward.isNotEmpty()) && days != 0
            // Until it can be added, it says what's missing.
            KavButton(
                when {
                    lines.isEmpty() && toward.isEmpty() -> if (numbers.isEmpty()) T("Choose where to", "בחרו יעד") else T("Choose a line", "בחרו קו")
                    days == 0 -> T("Choose a day", "בחרו יום")
                    else -> T("Add alert", "הוספת התראה")
                },
                wide = true, enabled = ready,
                onClick = {
                        val alert = LineAlert(
                            System.currentTimeMillis(), key, net.stopName(stop), numbers.filter { it in lines }, days, from, to,
                            toward = ends.filter { it in toward }.map { LineAlert.End(net.name[it], net.code[it], net.lat[it], net.lon[it]) },
                        )
                        model.saveAlerts(ctx, model.lineAlerts + alert)
                        lines = emptySet()
                        toward = emptySet()
                        editing = null
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            NoteCard(
                if (numbers.isEmpty()) T(
                    "On those days and hours, the next trains to these places stay in your notifications, refreshed about every minute.",
                    "בימים ובשעות האלה, הרכבות הבאות ליעדים האלה יופיעו בהתראות, ויתעדכנו בערך כל דקה.",
                ) else T(
                    "On those days and hours, the next buses of these lines stay in your notifications, refreshed about every minute.",
                    "בימים ובשעות האלה, האוטובוסים הבאים של הקווים האלה יופיעו בהתראות, ויתעדכנו בערך כל דקה.",
                ),
                Modifier.padding(top = K.gap3),
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = K.type.caption, modifier = Modifier.padding(top = K.gap5, bottom = K.gap2))
}

// A day of the week, as a chip is: filled with the accent while chosen. Seven share a row, so no chip's padding.
@Composable
private fun DayToggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (on) K.accent else K.fill, tween(150), label = "dayFill")
    val ink by animateColorAsState(if (on) K.onAccent else K.textSecondary, tween(150), label = "dayInk")
    Box(
        modifier.height(40.dp).clip(K.capsule)
            .background(fill)
            .semantics { selected = on }
            .clickable(role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = K.type.subhead, color = ink, maxLines = 1)
    }
}

// One end of the hours, as a field is: what it's set to, outlined in the accent while its wheels are open.
@Composable
private fun TimeButton(label: String, minutes: Int, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 52.dp).clip(K.capsule).background(K.fill)
            .border(1.5.dp, if (open) K.accent else Color.Transparent, K.capsule)
            .semantics(mergeDescendants = true) { selected = open }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = K.type.subhead, modifier = Modifier.weight(1f))
        Text(hhmm(minutes * 60), style = K.type.bodyStrong.tabular, color = if (open) K.accentInk else K.text)
    }
}

private val WheelRow = 36.dp

// An hour and a minute, five minutes apart, on wheels as a clock app sets them; AM and PM for anyone who asked for them.
@Composable
private fun TimeWheels(minutes: Int, onChange: (Int) -> Unit) {
    val hour = minutes / 60
    val minute = minutes % 60
    Box(Modifier.fillMaxWidth().padding(top = K.gap2), contentAlignment = Alignment.Center) {
        // The band the chosen time sits in.
        Box(Modifier.fillMaxWidth().height(WheelRow).clip(RoundedCornerShape(K.rTile)).background(K.fill))
        // Hours, then minutes, in either language.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap1)) {
                if (Shown.twelveHour) {
                    Wheel(12, (hour + 11) % 12, { "${it + 1}" }, T("Hour", "שעה")) { i ->
                        onChange((((i + 1) % 12) + if (hour >= 12) 12 else 0) * 60 + minute)
                    }
                } else {
                    Wheel(24, hour, { "%02d".format(Locale.US, it) }, T("Hour", "שעה")) { h -> onChange(h * 60 + minute) }
                }
                Text(":", fontSize = 22.sp, color = K.text)
                Wheel(12, minute / 5, { "%02d".format(Locale.US, it * 5) }, T("Minute", "דקה")) { m -> onChange(hour * 60 + m * 5) }
                if (Shown.twelveHour) {
                    Wheel(2, if (hour >= 12) 1 else 0, { if (it == 0) "AM" else "PM" }, "AM PM", loop = false) { p ->
                        onChange((hour % 12 + 12 * p) * 60 + minute)
                    }
                }
            }
        }
    }
}

// count values on a wheel five rows high, the chosen one in the middle. A looping wheel starts halfway down a long run
// of repeats, so it turns either way as far as anyone will turn it.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Wheel(count: Int, index: Int, label: (Int) -> String, description: String, loop: Boolean = true, onIndex: (Int) -> Unit) {
    val picked by rememberUpdatedState(onIndex)
    val total = if (loop) count * 400 else count
    val state = rememberLazyListState(initialFirstVisibleItemIndex = if (loop) count * 200 + index else index)
    val centre by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: state.firstVisibleItemIndex
        }
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state) {
        snapshotFlow { centre }.drop(1).distinctUntilChanged().collect {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            picked(it % count)
        }
    }
    val scope = rememberCoroutineScope()
    LazyColumn(
        Modifier.width(64.dp).height(WheelRow * 5)
            .semantics { contentDescription = description; stateDescription = label(centre % count) },
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = WheelRow * 2),
    ) {
        items(total) { i ->
            val away = abs(i - centre)
            Box(
                Modifier.fillMaxWidth().height(WheelRow)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        scope.launch { state.animateScrollToItem(i) }
                    }
                    .graphicsLayer { alpha = when (away) { 0 -> 1f; 1 -> .55f; else -> .25f } },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(i % count), fontSize = if (away == 0) 22.sp else 19.sp, color = K.text)
            }
        }
    }
}

private fun dayName(d: Int): String =
    if (T.rtl) listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")[d] else listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[d]

// "Sun-Thu · 08:00-09:00": an alert's days and hours.
internal fun alertWhen(a: LineAlert): String {
    val on = (0..6).filter { a.days and (1 shl it) != 0 }
    val runs = on.size > 2 && on.last() - on.first() == on.size - 1
    val days = when {
        on.size == 7 -> T("Every day", "כל יום")
        runs -> "${dayName(on.first())}-${dayName(on.last())}"
        else -> on.joinToString(", ") { dayName(it) }
    }
    return "$days · ${hhmm(a.from * 60)}-${hhmm(a.to * 60)}"
}

// "12, 18", or "To Tel Aviv Center" at a station: what an alert is for.
internal fun alertWhat(a: LineAlert): String =
    if (a.toward.isNotEmpty()) T("To ", "אל ") + a.toward.joinToString(", ") { shownName(it.name) } else a.lines.joinToString(", ")

// "12, 18 · Sun-Thu · 08:00-09:00": its lines, days and hours.
internal fun alertSummary(a: LineAlert): String = "${alertWhat(a)} · ${alertWhen(a)}"

// Where the trains leaving a stop end, the most trains first: for alerts at a station, which numbers no line.
internal fun Net.endsAt(stop: Int): List<Int> {
    val trains = HashMap<String, Int>()
    val first = HashMap<String, Int>()
    for (i in dStart[stop] until dStart[stop + 1]) {
        val end = tripLast(tripOf(cST[dConn[i]]))
        if (name[end] == name[stop]) continue
        trains[name[end]] = (trains[name[end]] ?: 0) + 1
        first.putIfAbsent(name[end], end)
    }
    return trains.entries.sortedByDescending { it.value }.map { first.getValue(it.key) }
}

// The numbered lines leaving a stop, in number order.
internal fun Net.linesAt(stop: Int): List<String> {
    val out = HashSet<String>()
    for (i in dStart[stop] until dStart[stop + 1]) rShort[tripRoute[tripOf(cST[dConn[i]])]].takeIf { it.isNotBlank() }?.let(out::add)
    return out.sortedWith(compareBy({ it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it }))
}
