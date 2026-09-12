package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import uk.noammm.kav.data.MapFile

/**
 * The offer to download the map, shown at launch while the archive is not here:
 * the first launch after updating from a Kav that drew its map online, and any
 * launch after the file has gone missing. A fresh install never reaches it, because
 * setup does not finish until the map is down. Saying Later closes it until the next
 * launch; the map pane keeps making the same offer meanwhile.
 */
@Composable
fun MapPrompt() {
    var dismissed by remember { mutableStateOf(false) }
    val state = MapFile.state
    if (dismissed || state is MapFile.State.Ready) return
    val ctx = LocalContext.current
    Dialog(onDismissRequest = { dismissed = true }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Download the map", "הורדת המפה"), fontSize = 20.sp, color = K.text, fontWeight = FontWeight.SemiBold)
            Text(
                T(
                    "Kav keeps its map on your phone instead of loading tiles from a server as " +
                        "you go, so nothing tracks where you look. It's about ${MapFile.BYTES shr 20} MB for " +
                        "all of Israel, downloaded once. After that the map works with no signal.",
                    "Kav שומרת את המפה על הטלפון שלכם במקום לטעון אריחים משרת תוך כדי תנועה, " +
                        "כך שאף אחד לא עוקב אחרי מה שאתם מסתכלים עליו. מדובר בכ-${MapFile.BYTES shr 20} מגה-בייט " +
                        "לכל ישראל, בהורדה חד-פעמית. אחר כך המפה עובדת גם בלי קליטה.",
                ),
                fontSize = 14.sp, color = K.muted, lineHeight = 20.sp,
            )
            when (state) {
                is MapFile.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)).background(K.surface4)) {
                        Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
                    }
                    Text(T("Downloading… ${(state.progress * 100).toInt()}%", "מורידים… ${(state.progress * 100).toInt()}%"), fontSize = 12.sp, color = K.dim)
                }
                is MapFile.State.Failed ->
                    Text(T("Couldn't download it. ${state.why}", "ההורדה נכשלה. ${state.why}"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp)
                else -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Box(
                    Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(K.rPill)).background(K.plateStrong)
                        .clickable(role = Role.Button) { dismissed = true },
                    contentAlignment = Alignment.Center,
                ) { Text(T("Later", "אחר כך"), fontSize = 15.sp, color = K.text) }
                val busy = state is MapFile.State.Downloading
                Box(
                    Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(K.rPill))
                        .background(if (busy) K.surface4 else K.accent)
                        .clickable(enabled = !busy, role = Role.Button) { MapFile.startDownload(ctx) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            busy -> T("Downloading…", "מורידים…")
                            state is MapFile.State.Failed -> T("Try again", "נסו שוב")
                            else -> T("Download", "הורדה")
                        },
                        fontSize = 15.sp, color = if (busy) K.muted else K.bg, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
