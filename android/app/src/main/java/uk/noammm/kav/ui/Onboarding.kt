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
import uk.noammm.kav.data.Timetable

@Composable
fun OnboardingScreen(model: KavModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var spoken by remember { mutableStateOf(Prefs.spokenAlerts(ctx)) }
    var startNow by remember { mutableStateOf(Prefs.beginTripsImmediately(ctx)) }
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
                        "Kav is grey with one colour on top. Slide along the colours, or take a preset; " +
                            "the preview follows as you go, and Settings has this again later.",
                        "Kav אפורה עם צבע אחד מעליה. החליקו לאורך הצבעים או בחרו גוון מוכן; " +
                            "התצוגה המקדימה מתעדכנת תוך כדי, ואפשר לשנות זאת שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap6))
                AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LineBadgeChoice() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { MapChoices() }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 3 }
            } else if (p == 3) {
                Text(T("Providers", "ספקים"), style = K.type.largeTitle)
                Text(
                    T(
                        "What Kav shares, and with whom. Settings has all of this again later.",
                        "מה Kav משתפת, ועם מי. אפשר לשנות את כל זה שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap3))
                // The same choices as Settings: where live locations come from, who plans trips, whose timetable lines
                // and stops show, and whether the week on the phone updates itself.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LiveSource() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PlanningSource() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { TimetableSource(); TimetableUpdates() }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 4 }
            } else if (p == 4) {
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
                Spacer(Modifier.height(K.gap5))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    OnTheWayChoices(spoken, startNow, { spoken = it }, { startNow = it }, setup = true)
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 5 }
            } else if (p == 5) {
                AutomaticUpdatesStep()
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { UpdatePrompts(checkAbove = false) }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 6 }
            } else {
                val state = MapFile.state
                val week = Timetable.state
                Text(T("Download the map and timetable", "הורדת המפה ולוח הזמנים"), style = K.type.largeTitle)
                Text(
                    T(
                        "Kav keeps its map on your phone instead of loading tiles from a server as " +
                            "you go, so nothing tracks where you look. It's about ${MapFile.BYTES shr 20} MB for all " +
                            "of Israel, once. After that the map works with no signal. Kav needs it " +
                            "before it can show you anything, so it downloads now. With it comes the week of scheduled " +
                            "departures, about 6 MB, built each night from the Ministry of Transport's timetable and " +
                            "kept up to date in the background.",
                        "Kav מחזיקה את המפה בטלפון שלכם במקום לטעון אריחים משרת תוך כדי תנועה, " +
                            "כך שאף אחד לא עוקב אחרי מה שאתם מסתכלים עליו. זה בערך ${MapFile.BYTES shr 20} MB לכל " +
                            "ישראל, פעם אחת. אחרי זה המפה עובדת גם בלי קליטה. Kav צריכה אותה כדי " +
                            "להציג לכם משהו, אז מורידים אותה עכשיו. איתה מגיע שבוע היציאות המתוכננות, בערך 6 MB, " +
                            "שנבנה כל לילה מלוח הזמנים של משרד התחבורה ומתעדכן ברקע.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                // Weight the combined progress by size, downloading the small timetable first.
                var started by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(started, week, state) {
                    if (started && week is Timetable.State.Ready && state is MapFile.State.Missing) MapFile.startDownload(ctx)
                }
                val weekDone = when (week) { is Timetable.State.Ready -> 1f; is Timetable.State.Downloading -> week.progress; else -> 0f }
                val mapDone = when (state) { is MapFile.State.Ready -> 1f; is MapFile.State.Downloading -> state.progress; else -> 0f }
                val total = (weekDone * WEEK_BYTES + mapDone * MapFile.BYTES) / (WEEK_BYTES + MapFile.BYTES)
                when {
                    state is MapFile.State.Ready && week is Timetable.State.Ready ->
                        NoteCard(T("Got it. The map and timetable stay on your phone from now on.", "מוכן. מעכשיו המפה ולוח הזמנים נשארים בטלפון שלכם."))
                    week is Timetable.State.Failed ->
                        Text(T("Couldn't download the timetable. ${week.why}", "ההורדה של לוח הזמנים לא הצליחה. ${week.why}"), style = K.type.subhead, color = K.critical)
                    state is MapFile.State.Failed ->
                        Text(T("Couldn't download the map. ${state.why}", "ההורדה של המפה לא הצליחה. ${state.why}"), style = K.type.subhead, color = K.critical)
                    started || week is Timetable.State.Downloading || state is MapFile.State.Downloading -> DownloadBar(
                        total,
                        if (week !is Timetable.State.Ready) T("Downloading the timetable…", "מורידים את לוח הזמנים…")
                        else T("Downloading the map…", "מורידים את המפה…"),
                    )
                    else -> {}
                }
                Spacer(Modifier.height(K.gap8))
                val start = {
                    started = true
                    if (week !is Timetable.State.Ready) Timetable.startDownload(ctx)
                    else if (state !is MapFile.State.Ready) MapFile.startDownload(ctx)
                }
                when {
                    state is MapFile.State.Ready && week is Timetable.State.Ready -> OnboardingButton(T("Done", "סיום"), onClick = onDone)
                    week is Timetable.State.Failed || state is MapFile.State.Failed -> OnboardingButton(T("Try again", "נסו שוב")) { start() }
                    started || state is MapFile.State.Downloading || week is Timetable.State.Downloading -> {}
                    else -> OnboardingButton(T("Download", "הורדה")) { start() }
                }
            }
            Spacer(Modifier.height(K.gap6))
        }
    }
    }
}

@Composable
private fun OnboardingButton(label: String, onClick: () -> Unit) = KavButton(label, onClick, wide = true)

// About the size of the week's download, for its share of setup's one bar with the map.
private const val WEEK_BYTES = 6_500_000f

@Composable
private fun DownloadBar(progress: Float, what: String = T("Downloading…", "מורידים…")) = Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
    }
    Text("$what ${(progress * 100).toInt()}%", style = K.type.caption)
}

// A rider who set Kav up when it came with its timetable gets the week before anything else, once: Kav reads it
// everywhere. It starts at once, and Try again stays until it's in.
@Composable
internal fun TimetableStep() {
    val ctx = LocalContext.current
    val week = Timetable.state
    LaunchedEffect(Unit) { if (Timetable.state is Timetable.State.Missing) Timetable.startDownload(ctx) }
    Column(
        Modifier.fillMaxSize().background(K.bg).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(K.gap4),
    ) {
        Spacer(Modifier.height(K.gap6))
        Text(T("Getting the timetable", "מורידים את לוח הזמנים"), style = K.type.largeTitle)
        Text(
            T(
                "Kav no longer comes with its timetable. It downloads the week of scheduled departures, about 6 MB, built " +
                    "each night from the Ministry of Transport's timetable, and keeps it up to date in the background.",
                "Kav כבר לא מגיעה עם לוח הזמנים. היא מורידה את שבוע היציאות המתוכננות, בערך 6 MB, שנבנה כל לילה " +
                    "מלוח הזמנים של משרד התחבורה, ומעדכנת אותו ברקע.",
            ),
            style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
        )
        Spacer(Modifier.height(K.gap5))
        when (week) {
            is Timetable.State.Downloading -> DownloadBar(week.progress)
            is Timetable.State.Failed ->
                Text(T("Couldn't download it. ${week.why}", "ההורדה לא הצליחה. ${week.why}"), style = K.type.subhead, color = K.critical)
            else -> {}
        }
        Spacer(Modifier.height(K.gap8))
        if (week is Timetable.State.Failed || week is Timetable.State.Missing) {
            OnboardingButton(T("Try again", "נסו שוב")) { Timetable.startDownload(ctx) }
        }
    }
}
