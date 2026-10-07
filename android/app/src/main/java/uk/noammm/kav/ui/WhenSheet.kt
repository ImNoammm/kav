package uk.noammm.kav.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
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
    val picker = rememberTimePickerState(
        initialHour = start.get(Calendar.HOUR_OF_DAY),
        initialMinute = start.get(Calendar.MINUTE),
        is24Hour = !Shown.twelveHour,
    )

    BottomSheet(onDismiss, scrolls = true) { close ->
        val chosen = mode
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (chosen) {
                    Moovit.TIME_ARRIVAL -> T("Arrive by", "הגעה עד")
                    Moovit.TIME_DEPARTURE -> T("Depart at", "יציאה בשעה")
                    else -> T("When", "מתי")
                },
                fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (chosen == null) T("Close", "סגירה") else T("Back", "חזרה"),
                fontSize = 14.sp, color = K.accent,
                modifier = Modifier.clip(RoundedCornerShape(K.rPill))
                    .clickable(role = Role.Button) { if (chosen == null) close(onDismiss) else mode = null }
                    .padding(horizontal = K.gap2, vertical = K.gap1),
            )
        }
        Spacer(Modifier.height(K.gap3))

        if (chosen == null) {
            WhenRow(T("Set departure time", "קביעת שעת יציאה")) { mode = Moovit.TIME_DEPARTURE }
            WhenRow(T("Set desired arrival time", "קביעת שעת הגעה רצויה")) { mode = Moovit.TIME_ARRIVAL }
            WhenRow(T("Latest departure", "היציאה האחרונה")) { close { onPick(0L, Moovit.TIME_LAST) } }
            val resettable = departAt > 0L || timeType == Moovit.TIME_LAST
            WhenRow(T("+15 min", "${T.ltr("+15")} דק'"), last = !resettable) {
                close { onPick(System.currentTimeMillis() + 15 * 60_000L, Moovit.TIME_DEPARTURE) }
            }
            if (resettable) WhenRow(T("Leave now", "צאו עכשיו"), tint = K.accent, last = true) { close(onNow) }
        } else {
            TimeInput(picker)
            Spacer(Modifier.height(K.gap2))
            Row(
                Modifier.fillMaxWidth().panel(K.rControl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Step(T.backward, day > 0, T("Previous day", "היום הקודם")) { day-- }
                Text(
                    dayLabel(today, day),
                    fontSize = 15.sp, color = K.text,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Step(T.onward, day < 14, T("Next day", "היום הבא")) { day++ }
            }
            Spacer(Modifier.height(K.gap3))
            val pickedToday = chosenMillis(today, day, picker.hour, picker.minute)
            val rolled = day == 0 && pickedToday < openedMinute
            val picked = if (rolled) chosenMillis(today, day + 1, picker.hour, picker.minute) else pickedToday
            if (rolled) {
                Text(T("That time has passed today, this will be for tomorrow.", "השעה הזו כבר עברה היום, הנסיעה תתוכנן למחר."), fontSize = 13.sp, color = K.dim)
                Spacer(Modifier.height(K.gap2))
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .panel(K.rControl)
                    .clickable(role = Role.Button) {
                        val (ms, type) = clampDepart(picked, chosen, System.currentTimeMillis())
                        close { onPick(ms, type) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(T("Done", "סיום"), fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun ChoiceSheet(title: String, choices: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    BottomSheet(onDismiss, scrolls = true) { close ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                T("Close", "סגירה"), fontSize = 14.sp, color = K.accent,
                modifier = Modifier.clip(RoundedCornerShape(K.rPill))
                    .clickable(role = Role.Button) { close(onDismiss) }
                    .padding(horizontal = K.gap2, vertical = K.gap1),
            )
        }
        Spacer(Modifier.height(K.gap3))
        choices.forEachIndexed { i, label ->
            WhenRow(label, tint = if (i == selected) K.accent else K.text, last = i == choices.lastIndex, checked = i == selected) {
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
            Text(label, fontSize = 15.sp, color = tint, modifier = Modifier.weight(1f))
            if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = K.accent, modifier = Modifier.size(18.dp))
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(K.border))
    }
}

@Composable
private fun Step(glyph: String, enabled: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(K.rControl))
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 22.sp, color = if (enabled) K.text else K.border)
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
    if (pickedMs <= now) 0L to Moovit.TIME_DEPARTURE else pickedMs to timeType

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
