package uk.noammm.kav.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Moovit

/** Gett's documented order-screen link. The drop-off is this TAXI leg's end. */
internal fun gettUri(leg: Moovit.Leg): Uri? {
    if (leg.kind != Moovit.LegKind.TAXI) return null
    val pickup = leg.taxiPickup ?: leg.shape.takeIf { it.size >= 2 }?.first()
    val dropoff = leg.taxiDropoff ?: leg.shape.takeIf { it.size >= 2 }?.last()
    fun valid(p: Pair<Double, Double>?): Boolean = p != null &&
        p.first.isFinite() && p.second.isFinite() && p.first in -90.0..90.0 && p.second in -180.0..180.0
    if (!valid(pickup) || !valid(dropoff)) return null
    return Uri.Builder().scheme("gett").authority("order")
        .appendQueryParameter("pickup_latitude", pickup!!.first.toString())
        .appendQueryParameter("pickup_longitude", pickup.second.toString())
        .appendQueryParameter("dropoff_latitude", dropoff!!.first.toString())
        .appendQueryParameter("dropoff_longitude", dropoff.second.toString())
        .build()
}

@Composable
fun GettButton(leg: Moovit.Leg, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = gettUri(leg)
    Row(
        modifier.heightIn(min = 48.dp).glassSurface(24.dp)
            .clickable(enabled = uri != null, role = Role.Button) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.gettaxi.android"))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Install Gett to open this taxi journey.", Toast.LENGTH_LONG).show()
                }
            }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeGlyph(Mode.TAXI, K.muted, 18.dp)
        Text(if (uri == null) "Taxi details unavailable" else "Open Gett", fontSize = 13.sp,
            color = if (uri == null) K.dim else K.text, fontWeight = FontWeight.Medium)
        if (uri != null) Text("↗", fontSize = 16.sp, color = K.muted)
    }
}
