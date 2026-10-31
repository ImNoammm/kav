package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Transit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.shape.CircleShape

@Composable
fun WhenSheet(
    departAt: Long,
    timeType: Int,
    onPick: (whenMs: Long, timeType: Int) -> Unit,
    onNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf<Int?>(null) }
    val today = remember { Calendar.getInstance(ISRAEL) }
    val openedMinute = remember { System.currentTimeMillis() / 60_000 * 60_000 }
    val start = remember(departAt) {
        Calendar.getInstance(ISRAEL).apply {
            timeInMillis = if (departAt > 0L) departAt else System.currentTimeMillis()
        }
    }
    var day by remember(departAt) {
        mutableIntStateOf(if (departAt > 0L) daysBetween(today, start).coerceAtLeast(0) else 0)
    }
    var hour by remember(departAt) { mutableIntStateOf(start.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember(departAt) { mutableIntStateOf(start.get(Calendar.MINUTE)) }

    BottomSheet(onDismiss, scrolls = true) { close ->
        val chosen = mode
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (chosen) {
                    Transit.TIME_ARRIVAL -> T("Arrive by", "הגעה עד")
                    Transit.TIME_DEPARTURE -> T("Depart at", "יציאה בשעה")
                    else -> T("When", "מתי")
                },
                style = K.type.headline,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (chosen == null) T("Close", "סגירה") else T("Back", "חזרה"),
                fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clip(K.capsule)
                    .clickable(role = Role.Button) { if (chosen == null) close(onDismiss) else mode = null }
                    .padding(horizontal = K.gap2, vertical = K.gap1),
            )
        }
        Spacer(Modifier.height(K.gap3))

        if (chosen == null) {
            WhenRow(T("Set departure time", "קביעת שעת יציאה")) { mode = Transit.TIME_DEPARTURE }
            WhenRow(T("Set desired arrival time", "קביעת שעת הגעה רצויה")) { mode = Transit.TIME_ARRIVAL }
            WhenRow(T("Latest departure", "היציאה האחרונה")) { close { onPick(0L, Transit.TIME_LAST) } }
            val resettable = departAt > 0L || timeType == Transit.TIME_LAST
            WhenRow(T("+15 min", "${T.ltr("+15")} דק׳"), last = !resettable) {
                close { onPick(System.currentTimeMillis() + 15 * 60_000L, Transit.TIME_DEPARTURE) }
            }
            if (resettable) WhenRow(T("Leave now", "צאו עכשיו"), tint = K.accentInk, last = true) { close(onNow) }
        } else {
            TimeEntry(hour, minute) { h, m -> hour = h; minute = m }
            Spacer(Modifier.height(K.gap2))
            Row(
                Modifier.fillMaxWidth().clip(K.capsule).background(K.fill),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Step(true, day > 0, T("Previous day", "היום הקודם")) { day-- }
                Text(
                    dayLabel(today, day),
                    style = K.type.body,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Step(false, day < 14, T("Next day", "היום הבא")) { day++ }
            }
            Spacer(Modifier.height(K.gap3))
            val pickedToday = chosenMillis(today, day, hour, minute)
            val rolled = day == 0 && pickedToday < openedMinute
            val picked = if (rolled) chosenMillis(today, day + 1, hour, minute) else pickedToday
            if (rolled) {
                NoteCard(T("That time has passed today, this will be for tomorrow.", "השעה הזו כבר עברה היום, הנסיעה תתוכנן למחר."))
                Spacer(Modifier.height(K.gap2))
            }
            KavButton(T("Done", "סיום"), {
                val (ms, type) = clampDepart(picked, chosen, System.currentTimeMillis())
                close { onPick(ms, type) }
            }, wide = true)
        }
    }
}

// The time typed in, as Kav writes times: "14:05", or "2:05" with AM or PM marked. Material's TimeInput always writes
// the hour as "02".
@Composable
internal fun TimeEntry(hour: Int, minute: Int, onChange: (hour: Int, minute: Int) -> Unit) {
    val twelve = Shown.twelveHour
    val pm = hour >= 12
    val minutes = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    Row(verticalAlignment = Alignment.Top) {
        TimeField(
            if (twelve) (hour + 11) % 12 + 1 else hour, pad = !twelve, range = if (twelve) 1..12 else 0..23,
            label = T("Hour", "שעה"), ime = ImeAction.Next, onFilled = { minutes.requestFocus() },
        ) { h -> onChange(if (twelve) h % 12 + (if (pm) 12 else 0) else h, minute) }
        Text(":", fontSize = 44.sp, color = K.text, modifier = Modifier.padding(horizontal = K.gap2, vertical = 8.dp))
        TimeField(
            minute, pad = true, range = 0..59, label = T("Minute", "דקה"), ime = ImeAction.Done,
            onFilled = { focus.clearFocus() }, modifier = Modifier.focusRequester(minutes),
        ) { m -> onChange(hour, m) }
        if (twelve) {
            Spacer(Modifier.width(K.gap3))
            Column(
                Modifier.clip(RoundedCornerShape(K.rTile)).border(1.dp, K.borderStrong, RoundedCornerShape(K.rTile)),
            ) {
                Period("AM", !pm) { if (pm) onChange(hour - 12, minute) }
                Box(Modifier.width(64.dp).height(1.dp).background(K.borderStrong))
                Period("PM", pm) { if (!pm) onChange(hour + 12, minute) }
            }
        }
    }
}

// A number shown as it is. Tapped, it's all selected, so the first digit typed replaces it; once it can't take another
// digit the next field takes over.
@Composable
private fun TimeField(
    value: Int, pad: Boolean, range: IntRange, label: String, ime: ImeAction, onFilled: () -> Unit,
    modifier: Modifier = Modifier, onValue: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf<String?>(null) }
    val shown = if (pad) "%02d".format(Locale.US, value) else value.toString()
    val valid = typed.isNullOrEmpty() || typed?.toIntOrNull()?.let { it in range } == true
    val field = when {
        typed != null -> TextFieldValue(typed.orEmpty(), TextRange(typed.orEmpty().length))
        focused -> TextFieldValue(shown, TextRange(0, shown.length))
        else -> TextFieldValue(shown, TextRange(shown.length))
    }
    Column {
        BasicTextField(
            field,
            onValueChange = { t ->
                // The tap itself reports the old number back with the caret moved: that's not typing.
                if (t.text == field.text) return@BasicTextField
                val digits = t.text.filter(Char::isDigit).take(2)
                typed = digits
                val v = digits.toIntOrNull()?.takeIf { it in range } ?: return@BasicTextField
                onValue(v)
                if (digits.length == 2 || v * 10 > range.last) onFilled()
            },
            modifier = modifier.width(96.dp).height(72.dp)
                .border(if (focused) 2.dp else 1.dp, if (focused) K.accent else K.borderStrong, RoundedCornerShape(K.rTile))
                .onFocusChanged { focused = it.isFocused; typed = null },
            textStyle = TextStyle(fontSize = 44.sp, color = if (valid) K.text else K.critical, textAlign = TextAlign.Center),
            singleLine = true,
            cursorBrush = SolidColor(K.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ime),
            keyboardActions = KeyboardActions(onNext = { onFilled() }, onDone = { onFilled() }),
            decorationBox = { box ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Rubbed out, what it was stays faintly in place.
                    if (typed == "") Text(shown, fontSize = 44.sp, color = K.dim)
                    box()
                }
            },
        )
        Text(label, style = K.type.subhead, modifier = Modifier.padding(top = K.gap1))
    }
}

@Composable
private fun Period(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp, 44.dp).background(if (on) K.accent else Color.Transparent)
            .semantics { selected = on }
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (on) K.onAccent else K.dim)
    }
}

@Composable
fun ChoiceSheet(title: String, choices: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    BottomSheet(onDismiss, scrolls = true) { close ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = K.type.headline, modifier = Modifier.weight(1f))
            Text(
                T("Close", "סגירה"), fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clip(K.capsule)
                    .clickable(role = Role.Button) { close(onDismiss) }
                    .padding(horizontal = K.gap2, vertical = K.gap1),
            )
        }
        Spacer(Modifier.height(K.gap3))
        choices.forEachIndexed { i, label ->
            WhenRow(label, tint = if (i == selected) K.accentInk else K.text, last = i == choices.lastIndex, checked = i == selected) {
                close { onPick(i) }
            }
        }
    }
}

@Composable
private fun WhenRow(label: String, tint: Color = K.text, last: Boolean = false, checked: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = K.type.body, color = tint, modifier = Modifier.weight(1f))
            if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = K.accentInk, modifier = Modifier.size(18.dp))
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(K.border))
    }
}

@Composable
private fun Step(back: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (back) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null, tint = if (enabled) K.text else K.textTertiary, modifier = Modifier.size(24.dp),
        )
    }
}

private fun dayLabel(today: Calendar, offset: Int): String {
    val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, today)) return T("Today", "היום")
    val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    if (same(c, tomorrow)) return T("Tomorrow", "מחר")
    return SimpleDateFormat("EEE d MMM", T.locale).apply { timeZone = ISRAEL }.format(Date(c.timeInMillis))
}

internal fun clampDepart(pickedMs: Long, timeType: Int, now: Long): Pair<Long, Int> =
    if (pickedMs <= now) 0L to Transit.TIME_DEPARTURE else pickedMs to timeType

private fun daysBetween(from: Calendar, to: Calendar): Int {
    fun midnight(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((midnight(to) - midnight(from)) / 86_400_000.0).toInt()
}

private fun chosenMillis(anchor: Calendar, offset: Int, hour: Int, minute: Int): Long =
    (anchor.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
