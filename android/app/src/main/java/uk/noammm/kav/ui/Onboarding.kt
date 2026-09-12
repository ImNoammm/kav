package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.MapFile

/**
 * The first launch: choose the language, pick the colour, say what a plan may
 * contain, then fetch the map. All but the last are the same controls Settings has,
 * so nothing learned here has to be learned again; the last is the same offer the
 * map itself makes.
 *
 * Language comes first because every page after it is written in the answer.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var filters by remember { mutableStateOf(Prefs.filters(ctx)) }
    androidx.activity.compose.BackHandler(enabled = page > 0) { page-- }

    AnimatedContent(
        page, transitionSpec = { if (targetState > initialState) forward() else backward() }, label = "onboarding",
        modifier = Modifier.fillMaxSize().background(K.bg),
    ) { p ->
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(K.gap4),
        ) {
            Spacer(Modifier.height(K.gap6))
            if (p == 0) {
                // Asked in both languages, because at this point Kav has been told
                // neither, and the phone's own language is no evidence: stop and line
                // names arrive from Moovit in Hebrew whichever way the phone is set.
                Text("בחרו שפה", style = Display, fontSize = 26.sp)
                Text("Choose a language", style = Display, fontSize = 26.sp, color = K.dim)
                Spacer(Modifier.height(K.gap6))
                for (l in Lang.entries) {
                    OnboardingButton(l.label, lit = T.lang == l) { T.switchTo(l); Prefs.setLang(ctx, l) }
                    Spacer(Modifier.height(K.gap3))
                }
                Spacer(Modifier.height(K.gap5))
                OnboardingButton(T("Next", "הבא")) { page = 1 }
            } else if (p == 1) {
                Text(T("Pick a colour", "בחרו צבע"), style = Display, fontSize = 26.sp)
                Text(
                    T(
                        "Kav is grey with one colour on top. Drag the dot, or take a preset; " +
                            "the preview follows as you go, and Settings has this again later.",
                        "Kav אפורה עם צבע אחד מעליה. גררו את הנקודה או בחרו גוון מוכן; " +
                            "התצוגה המקדימה מתעדכנת תוך כדי, ואפשר לשנות זאת שוב בהגדרות.",
                    ),
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap6))
                AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 2 }
            } else if (p == 2) {
                Text(T("What should a plan show?", "מה מסלול יכול לכלול?"), style = Display, fontSize = 26.sp)
                Text(
                    T(
                        "Everything is on. Switch off what you never take and the planner " +
                            "leaves it out. This is the same list Settings keeps.",
                        "הכול פעיל. כבו את מה שאתם אף פעם לא נוסעים בו והמתכנן ישמיט אותו. " +
                            "זו אותה רשימה שנמצאת בהגדרות.",
                    ),
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                Box(Modifier.padding(horizontal = 0.dp)) {
                    FilterRows(filters) { f, on ->
                        filters = if (on) filters + f else filters - f
                        Prefs.setFilter(ctx, f, on)
                    }
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 3 }
            } else {
                val state = MapFile.state
                Text(T("Download the map", "הורדת המפה"), style = Display, fontSize = 26.sp)
                Text(
                    T(
                        "Kav keeps its map on your phone instead of loading tiles from a server as " +
                            "you go, so nothing tracks where you look. It's about ${MapFile.BYTES shr 20} MB for all " +
                            "of Israel, once. After that the map works with no signal. Kav needs it " +
                            "before it can show you anything, so it downloads now.",
                        "Kav מחזיקה את המפה בטלפון שלכם במקום לטעון אריחים משרת תוך כדי תנועה, " +
                            "כך שאף אחד לא עוקב אחרי מה שאתם מסתכלים עליו. זה בערך ${MapFile.BYTES shr 20} MB לכל " +
                            "ישראל, פעם אחת. אחרי זה המפה עובדת גם בלי קליטה. Kav צריכה אותה כדי " +
                            "להציג לכם משהו, אז מורידים אותה עכשיו.",
                    ),
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                when (state) {
                    is MapFile.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)).background(K.surface4)) {
                            Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
                        }
                        Text(T("Downloading… ${(state.progress * 100).toInt()}%", "מורידים… ${(state.progress * 100).toInt()}%"), fontSize = 12.sp, color = K.dim)
                    }
                    is MapFile.State.Failed ->
                        Text(T("Couldn't download it. ${state.why}", "ההורדה לא הצליחה. ${state.why}"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp)
                    is MapFile.State.Ready ->
                        Text(T("Got it. The map stays on your phone from now on.", "מוכן. מעכשיו המפה נשארת בטלפון שלכם."), fontSize = 13.sp, color = K.muted)
                    else -> {}
                }
                Spacer(Modifier.height(K.gap8))
                // The map is not optional here and there is no way past it: no "continue
                // while it downloads" and no Later. Both were exits taken mid-fetch, and
                // the rider who took one landed in an app still offering to download the
                // map on every screen. The choice is made once, before the download starts:
                // the only exit is Done, and Done only exists once the file is on disk. A
                // failed download leaves Try again instead. The spacer stays so the page
                // does not jump as the button under it changes.
                when (state) {
                    is MapFile.State.Ready -> OnboardingButton(T("Done", "סיום"), onClick = onDone)
                    is MapFile.State.Downloading -> {}
                    is MapFile.State.Failed -> OnboardingButton(T("Try again", "נסו שוב")) { MapFile.startDownload(ctx) }
                    else -> OnboardingButton(T("Download", "הורדה")) { MapFile.startDownload(ctx) }
                }
            }
            Spacer(Modifier.height(K.gap6))
        }
    }
}

@Composable
private fun OnboardingButton(label: String, lit: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(K.rPill))
            .background(if (lit) K.accent else K.plate)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = if (lit) K.bg else K.text, fontWeight = FontWeight.Medium)
    }
}
