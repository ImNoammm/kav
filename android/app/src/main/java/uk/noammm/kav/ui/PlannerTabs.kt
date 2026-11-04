package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Source

// Every planner's routes for one search (KavPlan.everyPlanner): asked all at once, so a tab opens on its routes as soon
// as they're in. Another search, or Try again, asks them all again.
internal class EveryPlanner(private val scope: CoroutineScope) {
    private var search: List<Any?>? = null
    private val asked = HashMap<Source, Deferred<Result<KavPlan.Planned>>>()

    // The routes of planner for search, with every tab's planner asked too. Called on the main thread.
    fun routes(search: List<Any?>, planner: Source, tabs: List<Source>, ask: (Source) -> KavPlan.Planned): Deferred<Result<KavPlan.Planned>> {
        if (search != this.search) {
            asked.values.forEach { it.cancel() }
            asked.clear()
            this.search = search
        }
        fun start(s: Source) = asked.getOrPut(s) { scope.async(Dispatchers.IO) { runCatching { ask(s) } } }
        val wanted = start(planner)
        tabs.forEach(::start)
        return wanted
    }
}

// A tab for each planner, right under Earlier and Later.
@Composable
internal fun PlannerTabs(tabs: List<Source>, planner: Source, onPick: (Source) -> Unit, modifier: Modifier = Modifier) {
    KavSegmented(tabs.map(::sourceName), tabs.indexOf(planner).coerceAtLeast(0), { i -> onPick(tabs[i]) }, modifier)
}

// Settings: every planner's routes, a tab each, in the order set here. Results open on the tab of the planner chosen
// above. Each search then goes to every planner, which the text says.
@Composable
internal fun EveryPlannerChoice() {
    val ctx = LocalContext.current
    InfoHeader(
        T("Routes from every planner", "מסלולים מכל המתכננים"),
        T(
            "Results get a tab for each planner under Earlier and Later, and open on the planner chosen above. Each search then goes to every planner: each gets the trip's two points and time, and your IP address.",
            "בתוצאות יש לשונית לכל מתכנן מתחת למוקדם יותר ומאוחר יותר, והן נפתחות על המתכנן שנבחר למעלה. כל חיפוש נשלח אז לכל המתכננים: כל אחד מקבל את שתי הנקודות והשעה של המסלול, ואת כתובת ה-IP שלכם.",
        ),
    )
    // A planner moved up or down the tabs: swapped with its neighbour among those shown.
    fun move(s: Source, by: Int) {
        val shown = KavPlan.tabs()
        val other = shown.getOrNull(shown.indexOf(s) + by) ?: return
        val order = KavPlan.tabOrder.toMutableList()
        val a = order.indexOf(s)
        val b = order.indexOf(other)
        order[a] = other
        order[b] = s
        KavPlan.tabOrder = order
        Prefs.setPlannerTabs(ctx, order)
    }
    ListGroup(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        row {
            ToggleRow(
                T("A tab for each planner", "לשונית לכל מתכנן"), KavPlan.everyPlanner,
                { Icon(Icons.Rounded.Tab, null, tint = if (KavPlan.everyPlanner) K.text else K.dim, modifier = Modifier.size(22.dp)) },
            ) { on -> KavPlan.everyPlanner = on; Prefs.setEveryPlanner(ctx, on) }
        }
        if (KavPlan.everyPlanner) {
            val shown = KavPlan.tabs()
            // Each place keeps its row; a planner moved fades into its new place as the one it swapped with fades out.
            shown.forEachIndexed { i, s ->
                row {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = K.gap4, end = K.gap1),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(K.gap3),
                    ) {
                        Text("${i + 1}", style = K.type.subhead.tabular, color = K.textTertiary)
                        ChangingText(sourceName(s), Modifier.weight(1f)) { Text(it, style = K.type.body, maxLines = 1) }
                        IconButton({ move(s, -1) }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, T("Move ${sourceName(s)} up", "הזזת ${sourceName(s)} למעלה"),
                                tint = if (i > 0) K.text else K.dim)
                        }
                        IconButton({ move(s, 1) }, enabled = i < shown.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, T("Move ${sourceName(s)} down", "הזזת ${sourceName(s)} למטה"),
                                tint = if (i < shown.lastIndex) K.text else K.dim)
                        }
                    }
                }
            }
        }
    }
}
