package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * When to plan for.
 *
 * The rows are Moovit's own TimeQuickAction, in its order: DEPART_NOW, DEPART_AT,
 * ARRIVE_BY, TAKE_LAST_LINE, then its LATER shortcut. Only two of them open a
 * picker. TripPlanOptionsFragment (defpackage/rof.java) dispatches the tap:
 *
 *     if (i9 != 2) { if (i9 != 3) { if (i9 != 4) { ... } else {
 *         rofVar2.R1(new TripPlannerTime(TripPlannerTime.Type.LAST, -1L), 0L);
 *     } } else { rofVar2.S1(TripPlannerTime.Type.ARRIVE); }
 *     } else { rofVar2.S1(TripPlannerTime.Type.DEPART); }
 *
 * S1 opens the hour:minute dialog; R1 applies an option there and then. So
 * "Latest departure" asks for no time at all, it is a search mode ("search by
 * times for the last line", voice_over_tripplan_time_choose_last_hint), and the
 * time it carries is a placeholder. vpf.c confirms what goes on the wire for it:
 * `new MVTripPlanRequest(pref, tripPlanTime.b(), b(type), tripPlanTime.d(), ...)`
 * with TripPlanTime.b() falling back to now and d() false once the type is LAST,
 * which is what Moovit.tripPlanRequest already sends for TIME_LAST with no time.
 *
 * Moovit's promo row and its Moovit+ upsell are deliberately absent.
 * Its `reset_button` is here, as "Leave now".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhenSheet(
    departAt: Long,
    timeType: Int,
    onPick: (whenMs: Long, timeType: Int) -> Unit,
    onNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    // null = the list of plans; otherwise the MVTimeType being given a time
    var mode by remember { mutableStateOf<Int?>(null) }
    var day by remember { mutableIntStateOf(0) }
    val start = remember(departAt) {
        Calendar.getInstance().apply {
            timeInMillis = if (departAt > 0L) departAt else System.currentTimeMillis()
        }
    }
    val picker = rememberTimePickerState(
        initialHour = start.get(Calendar.HOUR_OF_DAY),
        initialMinute = start.get(Calendar.MINUTE),
        is24Hour = true,
    )

    Scrim(onDismiss) {
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
                    .clickable(role = Role.Button) { if (chosen == null) onDismiss() else mode = null }
                    .padding(horizontal = K.gap2, vertical = K.gap1),
            )
        }
        Spacer(Modifier.height(K.gap3))

        if (chosen == null) {
            WhenRow(T("Set departure time", "קביעת שעת יציאה")) { mode = Moovit.TIME_DEPARTURE }
            WhenRow(T("Set desired arrival time", "קביעת שעת הגעה רצויה")) { mode = Moovit.TIME_ARRIVAL }
            // no picker: R1, not S1, the mode is the whole answer
            WhenRow(T("Latest departure", "היציאה האחרונה")) { onPick(0L, Moovit.TIME_LAST) }
            // nothing to reset while the plan is already "now": no row, and no rule
            // under the one above it either
            val resettable = departAt > 0L || timeType == Moovit.TIME_LAST
            WhenRow(T("+15 min", "+15 דק'"), last = !resettable) {
                onPick(System.currentTimeMillis() + 15 * 60_000L, Moovit.TIME_DEPARTURE)
            }
            if (resettable) WhenRow(T("Leave now", "צאו עכשיו"), tint = K.accent, last = true) { onNow() }
        } else {
            TimeInput(picker)
            Spacer(Modifier.height(K.gap2))
            // Moovit's day bar: the date never leaves the picker, it steps beside it
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rControl)).background(K.plate),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Step(T.backward, day > 0) { day-- }
                Text(
                    dayLabel(start, day),
                    fontSize = 15.sp, color = K.text,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Step(T.onward, day < 14) { day++ }
            }
            Spacer(Modifier.height(K.gap3))
            val picked = chosenMillis(start, day, picker.hour, picker.minute)
            if (picked <= System.currentTimeMillis()) {
                Text(T("That time has passed, this will depart now.", "השעה הזו כבר עברה, הנסיעה תצא עכשיו."), fontSize = 13.sp, color = K.dim)
                Spacer(Modifier.height(K.gap2))
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(K.rControl)).background(K.plateStrong)
                    .clickable(role = Role.Button) {
                        val (ms, type) = clampDepart(picked, chosen, System.currentTimeMillis())
                        onPick(ms, type)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(T("Done", "סיום"), fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The panel Kav puts a sheet in: a scrim that dismisses, and a card that slides up. */
@Composable
private fun Scrim(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))
                    .clickable(onClick = onDismiss),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter = androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.9f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                ),
                initialOffsetY = { it },
            ),
            exit = androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(160),
                targetOffsetY = { it },
            ),
        ) {
            Column(
                // consume the tap so the panel itself does not dismiss
                Modifier.fillMaxWidth()
                    // The card sits on the bottom edge, and two things live there:
                    // the floating tab bar the pages deliberately draw behind, and
                    // the keyboard, which arrives on top of everything. Without this
                    // the last rows and the Done button end up under one or the
                    // other. Lift clear of whichever is taller, the keyboard when it
                    // is up, the tab bar (gesture inset included) when it is not,
                    // and let what is left scroll, so nothing can be out of reach.
                    .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                    .padding(K.gap3)
                    .clip(RoundedCornerShape(K.rCard)).background(K.surface1)
                    .clickable(enabled = false) {}
                    .verticalScroll(rememberScrollState())
                    .padding(K.gap4)
                    .animateContentSize(),
                content = content,
            )
        }
    }
}

@Composable
private fun WhenRow(label: String, tint: Color = K.text, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 15.sp, color = tint)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(K.border))
    }
}

@Composable
private fun Step(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(K.rControl))
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 22.sp, color = if (enabled) K.text else K.border)
    }
}

/** Today, tomorrow, then the weekday, the same ladder Moovit's day picker walks. */
private fun dayLabel(start: Calendar, offset: Int): String {
    val c = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, today)) return T("Today", "היום")
    today.add(Calendar.DAY_OF_YEAR, 1)
    if (same(c, today)) return T("Tomorrow", "מחר")
    return SimpleDateFormat("EEE d MMM", T.locale).format(Date(c.timeInMillis))
}

/**
 * A time already gone is not a plan.
 *
 * Moovit never lets one through either: TripPlanOptionsFragment opens its picker
 * with `bundle.putLong("minTime", System.currentTimeMillis())`, and its Earlier
 * button clamps through N1,
 *
 *     long max = Math.max(now, Math.min(maxTime, requested));
 *     if (DEPART.equals(type) && now == max) return TripPlannerTime.g();
 *
 * where g() is `new TripPlannerTime(Type.DEPART, -1L)`: no time, depart now.
 * Kav collapses an arrival in the past the same way rather than asking the server
 * to get somewhere before it was asked, a deliberate widening of Moovit's rule,
 * and the behaviour the app was asked for.
 *
 * Returns 0L as the time, which is Kav's own "depart now" everywhere else.
 */
internal fun clampDepart(pickedMs: Long, timeType: Int, now: Long): Pair<Long, Int> =
    if (pickedMs <= now) 0L to Moovit.TIME_DEPARTURE else pickedMs to timeType

private fun chosenMillis(start: Calendar, offset: Int, hour: Int, minute: Int): Long =
    (start.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
