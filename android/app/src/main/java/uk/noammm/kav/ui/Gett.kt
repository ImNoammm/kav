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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip

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

private fun openGett(context: android.content.Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.gettaxi.android"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, T("Install Gett to open this taxi journey.", "יש להתקין את Gett כדי לפתוח את נסיעת המונית."), Toast.LENGTH_LONG).show()
    }
}

// The step a navigation card is about: the whole button.
@Composable
fun GettButton(leg: Moovit.Leg, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = gettUri(leg)
    KavButton(
        if (uri == null) T("Taxi details unavailable", "פרטי המונית לא זמינים") else T("Open Gett", "פתחו ב-Gett"),
        onClick = { uri?.let { openGett(context, it) } },
        modifier = modifier, style = ButtonStyle.Secondary, icon = Icons.Rounded.OpenInNew, enabled = uri != null,
    )
}

// Gett where a route names it: the taxi and its name as one small pill that opens the ride in Gett.
@Composable
fun GettPill(leg: Moovit.Leg) {
    val context = LocalContext.current
    val uri = gettUri(leg)
    Row(
        Modifier.height(28.dp).clip(K.capsule).background(K.fill)
            .then(if (uri != null) Modifier.clickable(role = Role.Button, onClickLabel = T("Open Gett", "פתחו ב-Gett")) { openGett(context, uri) } else Modifier)
            .padding(horizontal = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap1),
    ) {
        ModeGlyph(Mode.TAXI, K.textSecondary, 18.dp)
        Text("Gett", style = K.type.subhead, color = K.text, fontWeight = FontWeight.Medium)
        if (uri != null) Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(14.dp))
    }
}
