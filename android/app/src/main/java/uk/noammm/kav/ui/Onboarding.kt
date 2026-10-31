package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.MapFile

@Composable
fun OnboardingScreen(model: KavModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    // A custom spot for search to see is picked on the whole screen, as in Settings.
    var choosing by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = page > 0) { page-- }

    Box(Modifier.fillMaxSize()) {
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
                Text("בחרו הגדרות אזוריות", style = K.type.largeTitle)
                Text("Choose a locale", style = K.type.largeTitle, color = K.textTertiary)
                Spacer(Modifier.height(K.gap6))
                KavSegmented(
                    Lang.entries.map { it.label }, selected = Lang.entries.indexOf(T.lang),
                    onSelect = { i -> val l = Lang.entries[i]; T.switchTo(l); Prefs.setLang(ctx, l) },
                )
                // A question of its own: the names of stops, apart from Kav's language.
                Text(
                    T("Stop names", "שמות תחנות"),
                    style = K.type.caption,
                    modifier = Modifier.padding(start = K.gap3, top = K.gap3, bottom = K.gap2),
                )
                StopNamesRow(ctx, Modifier)
                Text(
                    T("Time format", "תבנית שעה"),
                    style = K.type.caption,
                    modifier = Modifier.padding(start = K.gap3, top = K.gap3, bottom = K.gap2),
                )
                ClockChoice(ctx, Modifier)
                Spacer(Modifier.height(K.gap6))
                OnboardingButton(T("Next", "הבא")) { page = 1 }
            } else if (p == 1) {
                Text(T("Pick a look", "בחרו מראה"), style = K.type.largeTitle)
                Text(
                    T(
                        "OLED black, light or dark, with liquid glass or solid on top. " +
                            "The preview changes as you tap, and Settings has this again later.",
                        "שחור OLED, בהיר או כהה, עם זכוכית נוזלית או משטחים אטומים. " +
                            "התצוגה המקדימה משתנה כשאתם מקישים, ואפשר לשנות זאת שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap2))
                LookChoices {
                    Text(it, style = K.type.caption,
                        modifier = Modifier.padding(start = K.gap3, top = K.gap4, bottom = K.gap2))
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { Prefs.lookPicked(ctx); page = 2 }
            } else if (p == 2) {
                Text(T("Pick a colour", "בחרו צבע"), style = K.type.largeTitle)
                Text(
                    T(
                        "Kav is grey with one colour on top. Drag the dot, or take a preset; " +
                            "the preview follows as you go, and Settings has this again later.",
                        "Kav אפורה עם צבע אחד מעליה. גררו את הנקודה או בחרו גוון מוכן; " +
                            "התצוגה המקדימה מתעדכנת תוך כדי, ואפשר לשנות זאת שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap6))
                AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 3 }
            } else if (p == 3) {
                Text(T("What should a plan show?", "מה מסלול יכול לכלול?"), style = K.type.largeTitle)
                Text(
                    T(
                        "Everything is on. Switch off what you never take and the planner " +
                            "leaves it out. This is the same list Settings keeps.",
                        "הכול פעיל. כבו את מה שאתם אף פעם לא נוסעים בו והמתכנן ישמיט אותו. " +
                            "זו אותה רשימה שנמצאת בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap3))
                PlanChoices(model)
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 4 }
            } else if (p == 4) {
                val state = MapFile.state
                Text(T("Download the map", "הורדת המפה"), style = K.type.largeTitle)
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
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                when (state) {
                    is MapFile.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
                            Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
                        }
                        Text(T("Downloading… ${(state.progress * 100).toInt()}%", "מורידים… ${(state.progress * 100).toInt()}%"), style = K.type.caption)
                    }
                    is MapFile.State.Failed ->
                        Text(T("Couldn't download it. ${state.why}", "ההורדה לא הצליחה. ${state.why}"), style = K.type.subhead, color = K.critical)
                    is MapFile.State.Ready ->
                        NoteCard(T("Got it. The map stays on your phone from now on.", "מוכן. מעכשיו המפה נשארת בטלפון שלכם."))
                    else -> {}
                }
                Spacer(Modifier.height(K.gap8))
                when (state) {
                    is MapFile.State.Ready -> OnboardingButton(T("Next", "הבא")) { page = 5 }
                    is MapFile.State.Downloading -> {}
                    is MapFile.State.Failed -> OnboardingButton(T("Try again", "נסו שוב")) { MapFile.startDownload(ctx) }
                    else -> OnboardingButton(T("Download", "הורדה")) { MapFile.startDownload(ctx) }
                }
            } else if (p == 5) {
                AutomaticUpdatesStep()
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 6 }
            } else {
                Text(T("Privacy", "פרטיות"), style = K.type.largeTitle)
                Text(
                    T(
                        "What Kav shares, and with whom. Settings has all of this again later.",
                        "מה Kav משתפת, ועם מי. אפשר לשנות את כל זה שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap3))
                // The same choices as Settings, each Kav's server or the rider's own: where live locations come from, and
                // who plans trips.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LiveSource() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PlanningSource() }
                Spacer(Modifier.height(K.gap3))
                // PrivacyRows brings its own "Search" header.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PrivacyRows(model) { choosing = true } }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Done", "סיום"), onClick = onDone)
            }
            Spacer(Modifier.height(K.gap6))
        }
    }
    SeenPlacePicker(model, choosing) { choosing = false }
    }
}

@Composable
private fun OnboardingButton(label: String, onClick: () -> Unit) = KavButton(label, onClick, wide = true)
