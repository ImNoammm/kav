package uk.noammm.kav.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.noammm.kav.data.Transit
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import java.net.URLEncoder
import java.util.Locale

private const val GETT = "com.gettaxi.android"
private const val GETT_STORE = "https://play.google.com/store/apps/details?id=$GETT"

// Gett's own link for a ride (developer.gett.com, ride request): pickup and drop-off as coordinates, and their names
// where the rider gave them. Null when either end isn't a point.
internal fun gettLink(
    pickup: Pair<Double, Double>?, dropoff: Pair<Double, Double>?, pickupName: String? = null, dropoffName: String? = null,
): String? {
    fun valid(p: Pair<Double, Double>?): Boolean = p != null &&
        p.first.isFinite() && p.second.isFinite() && p.first in -90.0..90.0 && p.second in -180.0..180.0
    if (!valid(pickup) || !valid(dropoff)) return null
    fun at(v: Double) = String.format(Locale.US, "%.6f", v)
    fun name(key: String, v: String?) =
        v?.takeIf { it.isNotBlank() }?.let { "&$key=" + URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") }.orEmpty()
    return "gett://order?pickup_latitude=${at(pickup!!.first)}&pickup_longitude=${at(pickup.second)}" +
        "&dropoff_latitude=${at(dropoff!!.first)}&dropoff_longitude=${at(dropoff.second)}" +
        name("pickup_poi_name", pickupName) + name("dropoff_poi_name", dropoffName)
}

// A taxi leg's link, from the ends the plan gives it, else its road's.
internal fun gettLink(leg: Transit.Leg, pickupName: String? = null, dropoffName: String? = null): String? {
    if (leg.kind != Transit.LegKind.TAXI) return null
    val road = leg.shape.takeIf { it.size >= 2 }
    return gettLink(leg.taxiPickup ?: road?.first(), leg.taxiDropoff ?: road?.last(), pickupName, dropoffName)
}

// The ride in Gett when it's installed (the manifest's <queries> lets Android 11 and later see it), else Gett's page on
// the Play Store. Nothing leaves the phone before this tap.
private fun openGett(context: Context, link: String) {
    val order = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(GETT)
    val store = Intent(Intent.ACTION_VIEW, Uri.parse(GETT_STORE))
    try {
        context.startActivity(if (order.resolveActivity(context.packageManager) != null) order else store)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(store) }
    }
}

// Ordering the ride with Gett: one plain button.
@Composable
internal fun OrderGett(link: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    KavButton(
        if (link == null) T("Taxi details unavailable", "פרטי המונית לא זמינים") else T("Order with Gett", "הזמנה ב-Gett"),
        onClick = { link?.let { openGett(context, it) } },
        modifier = modifier, style = ButtonStyle.Secondary, icon = Icons.Rounded.OpenInNew, enabled = link != null,
    )
}

// The step a navigation card is about: the whole button.
@Composable
fun GettButton(leg: Transit.Leg, modifier: Modifier = Modifier) = OrderGett(gettLink(leg), modifier)

// Gett where a route names it: the taxi and its name as one small pill that opens the ride in Gett.
@Composable
fun GettPill(leg: Transit.Leg) {
    val context = LocalContext.current
    val link = gettLink(leg)
    Row(
        Modifier.height(28.dp).clip(K.capsule).background(K.fill)
            .then(if (link != null) Modifier.clickable(role = Role.Button, onClickLabel = T("Order with Gett", "הזמנה ב-Gett")) { openGett(context, link) } else Modifier)
            .padding(horizontal = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap1),
    ) {
        ModeGlyph(Mode.TAXI, K.textSecondary, 18.dp)
        Text("Gett", style = K.type.subhead, color = K.text, fontWeight = FontWeight.Medium)
        if (link != null) Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(14.dp))
    }
}

// A taxi the whole way, in the taxi section's place, for a plan that brought no taxi card (one for later, which the
// server offers only for leaving now): Gett and the button that orders it.
@Composable
internal fun TaxiRow(link: String) {
    SectionHeader(T("Taxi & ride hailing", "מוניות"))
    KavCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            ModeGlyph(Mode.TAXI, K.textSecondary, 22.dp)
            Text("Gett", style = K.type.body, color = K.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            OrderGett(link)
        }
    }
}

// Where the taxi section goes among the routes, as the plan orders its sections: before the first route of a section
// that comes after it, else after them all.
internal fun taxiSlot(plan: Transit.Plan, shown: List<Transit.Itinerary>): Int {
    val order = plan.sections.associate { it.id to it.index }
    val taxi = order[Transit.SECTION_TAXI] ?: return shown.size
    return shown.indexOfFirst { (order[it.sectionId] ?: Int.MAX_VALUE) > taxi }.let { if (it < 0) shown.size else it }
}
