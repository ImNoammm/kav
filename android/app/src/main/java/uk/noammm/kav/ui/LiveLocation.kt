package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.shownName
import java.util.Date
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.font.FontWeight

private val hm get() = clockFormat()

fun whenLabel(t: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val m = ((t - now) / 60).toInt()
    return when {
        m < 0 -> hm.format(Date(t * 1000))
        m == 0 -> T("now", "עכשיו")
        m < 60 -> T("in $m min", "בעוד $m דק׳")
        else -> hm.format(Date(t * 1000))
    }
}

// compact: the small size, for a card that's already inside another.
@Composable
fun LiveLocationButton(live: Boolean, tint: Color = K.realtime, compact: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.minimumInteractiveComponentSize().heightIn(min = if (compact) 40.dp else 48.dp).clip(K.capsule).background(K.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) K.gap4 else K.gap5),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        LiveGlyph(if (live) tint else K.textTertiary, if (compact) 16.dp else 18.dp)
        Text(
            T("Live location", "מיקום בזמן אמת"), style = if (compact) K.type.subhead else K.type.bodyStrong,
            fontWeight = FontWeight.Medium, color = if (live) K.text else K.textSecondary, maxLines = 1,
        )
    }
}

@Composable
fun LiveLocationScreen(
    leg: Transit.Leg,
    boardStopId: Int,
    r: Transit.Resolved,
    onBack: () -> Unit,
) {
    val own = r.arrival(leg)
    // Without a position of its own, where Kav's server has its trip: it places a train under way by its times though a
    // station's answer can have the train without one, and a train that has left the rider's station is still its run.
    val route = own?.vehicleId?.let(KavLive::routeOf) ?: KavPlan.routeOf(leg.lineId)?.takeIf { own == null && r.isTrain(leg) }
    val spots = rememberKavSpots(listOfNotNull(route?.takeIf { own?.hasLocation != true }))
    val arrival = route?.let { KavLive.followed(own, spots, it, leg.dep) } ?: own
    val info = r.line(leg.lineId)
    // Every second, for how long ago the position came.
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) { delay(1000); value = System.currentTimeMillis() / 1000 }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader(T("Live", "מיקום בזמן אמת"), "", back = onBack)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val agency = info?.agencyId ?: -1
            val rt = if (info != null) r.routeType(agency) else 3
            val plate = linePlate(rt, agency) { Operators.of(r, agency) }
            Row(
                Modifier.clip(RoundedCornerShape(K.rBadge)).background(plate?.fill ?: K.surface1)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgencyMark(rt, agency, plate?.ink ?: K.muted, 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    leg.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "…",
                    style = K.type.bodyStrong, color = plate?.ink ?: K.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
            }
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f)) {
                Text(
                    info?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "אל ${shownName(it)}") } ?: "",
                    fontSize = 14.sp, color = K.muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                r.agencyName(agency)?.let {
                    Text(it, fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        VehicleMap(
            leg, arrival, modeOf(if (info != null) r.routeType(info.agencyId) else 3),
            approach = lineRoute(arrival, r),
            modifier = Modifier.padding(horizontal = K.gap3),
        )

        Spacer(Modifier.height(K.gap3))
        StatusBlock(leg, boardStopId, arrival, r, now)
    }
}

fun lineRoute(a: Transit.Arrival?, r: Transit.Resolved): List<Pair<Double, Double>> {
    if (a == null || a.tripShapeId <= 0) return emptyList()
    return r.shapes[a.tripShapeId].orEmpty()
}

@Composable
private fun VehicleMap(
    leg: Transit.Leg,
    a: Transit.Arrival?,
    mode: Mode,
    approach: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (leg.shape.size < 2) return
    val bus = a?.takeIf { it.hasLocation }
    val points = leg.shape + approach + listOfNotNull(bus?.let { it.lat to it.lon })
    val geometry = remember(approach, leg, K.look, bus?.lat, bus?.lon, bus?.vehicleStatus) {
        // Off its route, a dashed line ties the bus to the nearest point of the route.
        val astray = bus?.takeIf { it.vehicleStatus == 2 }?.let { b ->
            val near = leg.shape.minBy { (lat, lon) -> metres(lat, lon, b.lat, b.lon) }
            MapLine(listOf(near, b.lat to b.lon), K.problem, 1.5f, dashed = true)
        }
        MapGeometry(
            lines = listOfNotNull(
                MapLine(approach, K.routeIdle, 3f, casing = 7f),
                MapLine(leg.shape, K.route, 4f, casing = 8f),
                astray,
            ),
            dots = listOf(
                MapDot(leg.shape.first().first, leg.shape.first().second, K.bg, 6f),
                MapDot(leg.shape.first().first, leg.shape.first().second, Color.Transparent, 5f, K.text, 2f),
                MapDot(leg.shape.last().first, leg.shape.last().second, K.bg, 7f),
                MapDot(leg.shape.last().first, leg.shape.last().second, K.text, 5f),
            ),
        )
    }
    TileMap(
        points,
        modifier.fillMaxWidth().height(260.dp).panel(K.rCard),
        geometry = geometry,
        live = vehicleGeometry(listOfNotNull(bus?.let { it to mode }), LocalDensity.current),
    )
}

@Composable
private fun StatusBlock(
    leg: Transit.Leg,
    boardStopId: Int,
    a: Transit.Arrival?,
    r: Transit.Resolved,
    now: Long,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3)
            .panel(K.rCard).padding(K.gap4),
    ) {
        val lineLive = r.liveFor(leg).any { it.hasLocation }
        val (headline, tint) = when {
            a?.status == 3 -> T("Canceled for this station", "מבוטל עבור תחנה זו") to K.critical
            a?.vehicleStatus == 3 -> T("Line not departed yet", "הקו טרם יצא") to K.dim
            a != null && !a.hasLocation && a.rtUtc > 0 -> T("There's a live time, but no position right now", "יש זמן בזמן אמת, אבל אין מיקום כרגע") to K.dim
            (a == null || !a.hasLocation) && lineLive -> T("Your bus hasn't set out yet", "האוטובוס שלכם עוד לא יצא לדרך") to K.dim
            a == null || !a.hasLocation -> T("This line doesn’t have a live location", "לקו הזה אין מיקום בזמן אמת") to K.dim
            a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
            else -> locationHeadline(a.sampleUtc, now)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a?.hasLocation == true) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
            Text(headline, style = K.type.bodyStrong, color = tint)
        }
        if (a != null && a.vehicleStatus == 2) {
            Text(
                T("This line has deviated from its planned route", "הקו הזה סטה מהמסלול המתוכנן"),
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
        }
        if (a != null && a.sampleUtc > 0) {
            Text(
                updatedLabel(a),
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
        }

        Spacer(Modifier.height(K.gap4))
        val nextStop = nextStopOnLeg(leg, a)
        if (nextStop != null) {
            Fact(T("Next stop", "התחנה הבאה"), r.stopName(nextStop) ?: "…")
        }
        val away = a?.stopsAway ?: -1
        if (away >= 0) Fact(if (away == 1) T("1 stop away", "תחנה אחת") else T("Stops away", "תחנות"), if (away == 1) "" else "$away")
        r.stopName(boardStopId)?.let { Fact(T("Your stop", "התחנה שלכם"), it) }
        if (a != null && a.rtUtc > 0) {
            Fact(T("Arriving", "הגעה"), whenLabel(a.rtUtc, now))
        }
        a?.let { KavLive.plateOf(it.vehicleId) }?.let { Fact(T("Number plate", "מספר רישוי"), it) }
    }
}

internal fun nextStopOnLeg(leg: Transit.Leg, arrival: Transit.Arrival?): Int? {
    if (arrival == null || arrival.nextStopIndex < 0 || arrival.stopIndex < 0) return null
    return leg.stops.getOrNull(arrival.nextStopIndex - arrival.stopIndex)
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Text(label, fontSize = 14.sp, color = K.dim, modifier = Modifier.width(112.dp))
        Text(
            value, fontSize = 14.sp, color = K.text, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}
