package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import uk.noammm.kav.data.MapFile

// "Later" lasts until the app restarts.
private var dismissed by mutableStateOf(false)

@Composable
fun MapPrompt() {
    val state = MapFile.state
    if (dismissed || state is MapFile.State.Ready) return
    val ctx = LocalContext.current
    Dialog(onDismissRequest = { dismissed = true }) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Download the map", "הורדת המפה"), style = K.type.title)
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
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
                        Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
                    }
                    Text(T("Downloading… ${(state.progress * 100).toInt()}%", "מורידים… ${(state.progress * 100).toInt()}%"), fontSize = 12.sp, color = K.dim)
                }
                is MapFile.State.Failed ->
                    Text(T("Couldn't download it. ${state.why}", "ההורדה נכשלה. ${state.why}"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp)
                else -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(
                    T("Later", "אחר כך"), { dismissed = true }, Modifier.weight(1f).heightIn(min = 52.dp),
                    style = ButtonStyle.Secondary,
                )
                val busy = state is MapFile.State.Downloading
                KavButton(
                    when {
                        busy -> T("Downloading…", "מורידים…")
                        state is MapFile.State.Failed -> T("Try again", "נסו שוב")
                        else -> T("Download", "הורדה")
                    },
                    { MapFile.startDownload(ctx) }, Modifier.weight(1f), enabled = !busy,
                )
            }
        }
    }
}
