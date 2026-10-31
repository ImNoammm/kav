package uk.noammm.kav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import uk.noammm.kav.Checks
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.Updates

// Kav downloading new versions by itself, to install when the rider says so (AutoUpdate): Settings' switch, and a step of
// setup that spells it out (explained). On unless turned off; it sends nothing about the rider, as the background check
// (Checks) only downloads.
@Composable
internal fun AutomaticUpdates(explained: Boolean = false) {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(Prefs.autoUpdate(ctx)) }
    // Read again whenever Kav is back from Android's settings.
    var mayInstall by remember { mutableStateOf(Updates.canInstall(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { mayInstall = Updates.canInstall(ctx) } }
    val title = T("Automatic updates", "עדכונים אוטומטיים")
    val info = T(
        "Once a day, at a time your phone picks and never because you opened it, Kav asks GitHub whether there's a " +
            "new version, and on Wi-Fi downloads it and makes sure it's signed with Kav's own key. Nothing " +
            "installs by itself: the next time you open Kav it asks, Install now or Later. Install now closes Kav to " +
            "update it, and Android or Google Play Protect may ask you to confirm. Kav sends nothing about you: GitHub " +
            "sees your IP address and the time, as for any download. While this is on, Tell me about new versions doesn't apply.",
        "פעם ביום, בשעה שהטלפון בוחר ואף פעם לא בגלל שפתחתם אותה, Kav שואלת את GitHub אם יש גרסה " +
            "חדשה, וב-Wi-Fi מורידה אותה ומוודאת שהיא חתומה במפתח של Kav. שום דבר לא מותקן מעצמו: בפעם הבאה " +
            "שתפתחו את Kav היא תשאל, להתקין עכשיו או אחר כך. התקנה סוגרת את Kav כדי לעדכן אותה, ו-Android או " +
            "Google Play Protect עשויים לבקש מכם לאשר. Kav לא שולחת שום דבר עליכם: GitHub רואה את כתובת ה-IP " +
            "שלכם ואת השעה, כמו בכל הורדה. כשזה פועל, עדכנו אותי על גרסאות חדשות לא חל.",
    )
    Column(Modifier.fillMaxWidth()) {
        if (explained) {
            SectionHeader(title)
            NoteCard(info, Modifier.padding(bottom = K.gap2))
        } else InfoHeader(title, info)
        ListGroup {
            row {
                ToggleRow(
                    T("Download updates automatically", "להוריד עדכונים אוטומטית"), auto,
                    { Icon(Icons.Rounded.SystemUpdate, null, tint = if (auto) K.text else K.dim, modifier = Modifier.size(22.dp)) },
                ) { on ->
                    auto = on
                    Prefs.setAutoUpdate(ctx, on)
                    Checks.ensure(ctx)
                }
            }
            if (auto && !mayInstall) row {
                ListRow(
                    T("Let Kav install updates", "לאפשר ל-Kav להתקין עדכונים"),
                    subtitle = T("Android asks this once", "Android שואלת את זה פעם אחת"),
                    onClick = { Updates.askInstallPermission(ctx) },
                )
            }
        }
    }
}

// The setup step: what it does, that nothing about the rider is sent, and its switch, on.
@Composable
internal fun AutomaticUpdatesStep() {
    Text(T("Automatic updates", "עדכונים אוטומטיים"), style = K.type.largeTitle)
    Text(
        T(
            "On: Kav downloads a new version on Wi-Fi and checks it's signed with Kav's own key. Nothing installs until " +
                "you tap Install now. Settings has this again later.",
            "פועל: Kav מורידה גרסה חדשה ב-Wi-Fi ובודקת שהיא חתומה במפתח של Kav. שום דבר לא מותקן עד שתקישו " +
                "להתקין עכשיו. אפשר לשנות את זה שוב בהגדרות.",
        ),
        style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { AutomaticUpdates(explained = true) }
}
