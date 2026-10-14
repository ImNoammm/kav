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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.Seen
import uk.noammm.kav.data.MapFile

@Composable
fun OnboardingScreen(model: KavModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var filters by remember { mutableStateOf(Prefs.filters(ctx)) }
    // A custom spot for Moovit to see is picked on the whole screen, as in Settings.
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        var q by remember { mutableStateOf("") }
        PlacePicker(
            title = T("a place for Moovit to see…", "מקום ש-Moovit יראה…"),
            here = null,
            allowMyLocation = false,
            onMyLocation = {},
            onPick = { p -> model.setSeen(ctx, Seen.PLACE, p); choosing = false },
            onDismiss = { choosing = false },
            favourites = model.favourites,
            onSaveFavourites = { model.saveFavourites(ctx, it) },
            query = q,
            onQuery = { q = it },
            net = model.net,
        )
        return
    }
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
                Text("בחרו הגדרות אזוריות", style = Display, fontSize = 26.sp)
                Text("Choose a locale", style = Display, fontSize = 26.sp, color = K.dim)
                Spacer(Modifier.height(K.gap6))
                for (l in Lang.entries) {
                    OnboardingButton(l.label, lit = T.lang == l) { T.switchTo(l); Prefs.setLang(ctx, l) }
                    Spacer(Modifier.height(K.gap3))
                }
                Text(
                    T("time format", "תבנית שעה"),
                    style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                    modifier = Modifier.padding(start = K.gap3, top = K.gap3, bottom = K.gap2),
                )
                ClockChoice(ctx, Modifier)
                Spacer(Modifier.height(K.gap6))
                OnboardingButton(T("Next", "הבא")) { page = 1 }
            } else if (p == 1) {
                Text(T("Pick a look", "בחרו מראה"), style = Display, fontSize = 26.sp)
                Text(
                    T(
                        "OLED black, light or dark, with liquid glass or solid on top. " +
                            "The preview changes as you tap, and Settings has this again later.",
                        "שחור OLED, בהיר או כהה, עם זכוכית נוזלית או משטחים אטומים. " +
                            "התצוגה המקדימה משתנה כשאתם מקישים, ואפשר לשנות זאת שוב בהגדרות.",
                    ),
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap2))
                LookChoices {
                    Text(it, style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                        modifier = Modifier.padding(start = K.gap3, top = K.gap4, bottom = K.gap2))
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { Prefs.lookPicked(ctx); page = 2 }
            } else if (p == 2) {
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
                OnboardingButton(T("Next", "הבא")) { page = 3 }
            } else if (p == 3) {
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
                Spacer(Modifier.height(K.gap5))
                Text(
                    T("what a card shows", "מה מוצג בכרטיס"),
                    style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                    modifier = Modifier.padding(start = K.gap3, bottom = K.gap2),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    SwitchRow(
                        T("Emissions", "פליטות"),
                        T(
                            "The CO2e figure on every plan and on the trip you open.",
                            "נתון ה-CO2e על כל מסלול ועל הנסיעה שאתם פותחים.",
                        ),
                        Shown.co2,
                        { on -> Shown.co2 = on; Prefs.setShowCo2(ctx, on) },
                    ) { GlobeGlyph(if (Shown.co2) K.text else K.dim, K.surface1, 18.dp) }
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 4 }
            } else if (p == 4) {
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
                when (state) {
                    is MapFile.State.Ready -> OnboardingButton(T("Next", "הבא")) { page = 5 }
                    is MapFile.State.Downloading -> {}
                    is MapFile.State.Failed -> OnboardingButton(T("Try again", "נסו שוב")) { MapFile.startDownload(ctx) }
                    else -> OnboardingButton(T("Download", "הורדה")) { MapFile.startDownload(ctx) }
                }
            } else {
                Text(T("Privacy", "פרטיות"), style = Display, fontSize = 26.sp)
                Text(
                    T(
                        "What Kav shares, and with whom. Settings has all of this again later.",
                        "מה Kav משתפת, ועם מי. אפשר לשנות את כל זה שוב בהגדרות.",
                    ),
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                Text(
                    T("live locations", "מיקום בזמן אמת"),
                    style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                    modifier = Modifier.padding(start = K.gap3, bottom = K.gap2),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LiveSourceRow() }
                Spacer(Modifier.height(K.gap5))
                Text(
                    T("search", "חיפוש"),
                    style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                    modifier = Modifier.padding(start = K.gap3, bottom = K.gap2),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PrivacyRows(model) { choosing = true } }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Done", "סיום"), onClick = onDone)
            }
            Spacer(Modifier.height(K.gap6))
        }
    }
}

@Composable
private fun OnboardingButton(label: String, lit: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(
                // A choice not taken is drawn as the rest of Kav draws its controls in the look picked.
                if (lit) Modifier.clip(RoundedCornerShape(K.rPill)).background(K.accent)
                else Modifier.panel(K.rPill, solid = true)
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = if (lit) K.onAccent else K.text, fontWeight = FontWeight.Medium)
    }
}
