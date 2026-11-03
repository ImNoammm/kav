package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.PendingBackup
import uk.noammm.kav.Pip
import uk.noammm.kav.Prefs
import uk.noammm.kav.Seen
import uk.noammm.kav.data.BusNearby
import uk.noammm.kav.data.Backup
import uk.noammm.kav.data.GoogleRecipe
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.SignedFile
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.Sources
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.Updates
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Place
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun SettingsScreen(model: KavModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var choosing by remember { mutableStateOf(false) }

    // Each part is a row saying what it's set to, and opens on its own page.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(page != null) { page = null }
    val parts = ArrayList<SettingsPart>()
    fun part(id: String, title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
        parts += SettingsPart(id, title, summary, content)
    }
    val waiting = model.update != null && Shown.updatePrompts

    part(
        "live",
        T("Live times and trips", "זמנים בזמן אמת ומסלולים"),
        serverUses(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            LiveSource()
            PlanningSource()
            TimetableSource()
            TimetableUpdates()
        }
    }

    // Most used first, the ones set once and forgotten after.
    part(
        "pay",
        T("Payments", "תשלומים"),
        when {
            !Shown.payments -> T("Hidden", "מוסתרים")
            Payer.signedIn -> T("Your Rav-Pass account and card", "חשבון רב-פס והכרטיס שלכם")
            else -> T("Pay for bus, train and light rail rides", "תשלום על נסיעות באוטובוס, ברכבת וברכבת הקלה")
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            InfoHeader(T("Paying", "תשלום"), T("On Home and on each ride. Off, Kav shows nothing about paying.", "במסך הבית ובכל נסיעה. כשכבוי, Kav לא מציגה שום דבר על תשלום."))
            ListGroup {
                row {
                    ToggleRow(T("Paying in Kav", "תשלום ב-Kav"), Shown.payments, { Canvas(Modifier.size(18.dp)) { drawQr() } }) { on ->
                        Shown.payments = on; Prefs.setShowPayments(ctx, on)
                    }
                }
            }
        }
        if (Payer.signedIn) {
            Spacer(Modifier.height(K.gap2))
            PaymentsSection(model)
        }
    }

    val offered = filtersOf(KavPlan.source)
    val ways = offered.count { it in model.filters }
    part(
        "plans",
        T("Planning", "תכנון"),
        if (ways == offered.size) T("Every way of getting there", "כל הדרכים להגיע")
        else T("$ways of ${offered.size} ways of getting there", "$ways מתוך ${offered.size} דרכים להגיע"),
    ) { PlanChoices(model) }

    var spoken by remember { mutableStateOf(Prefs.spokenAlerts(ctx)) }
    var startNow by remember { mutableStateOf(Prefs.beginTripsImmediately(ctx)) }
    part(
        "trip",
        T("On the way", "בדרך"),
        listOfNotNull(
            if (spoken) T("Alerts said out loud", "התראות בקול") else T("Alerts on the screen", "התראות על המסך"),
            if (Pip.allowed) T("small window when you leave Kav", "חלון קטן כשיוצאים מ-Kav")
            else T("no small window", "בלי חלון קטן"),
            if (startNow) T("trips start right away", "נסיעות מתחילות מיד") else null,
        ).joinToString(" · "),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            OnTheWayChoices(spoken, startNow, { spoken = it }, { startNow = it })
        }
    }

    part(
        "privacy",
        T("Privacy", "פרטיות"),
        when {
            !Prefs.privateSearch(ctx) -> T("Google sees where you are", "Google רואה איפה אתם")
            model.seen == Seen.CITY -> T("Private search: Google sees your town", "חיפוש פרטי: Google רואה את היישוב שלכם")
            model.seen == Seen.PLACE -> T("Private search: Google sees a place you picked", "חיפוש פרטי: Google רואה מקום שבחרתם")
            else -> T("Private search: Google sees no location", "חיפוש פרטי: Google לא רואה מיקום")
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            PrivacyRows(model) { choosing = true }
        }
    }

    part(
        "lang",
        T("Language and time", "שפה ושעה"),
        listOf(
            T.lang.label,
            if (StopNames.english) T("stop names in English", "שמות תחנות באנגלית") else T("stop names in Hebrew", "שמות תחנות בעברית"),
            if (Shown.twelveHour) T("12-hour clock", "שעון 12 שעות") else T("24-hour clock", "שעון 24 שעות"),
        ).joinToString(" · "),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            Sub(T("Kav's language", "השפה של Kav"))
            LanguageRow(ctx)
            InfoHeader(
                T("Stop names", "שמות תחנות"),
                T("Only the names of stops, stations and towns. Kav's own language stays as it is.",
                    "רק שמות התחנות, התחנות והיישובים. השפה של Kav נשארת כפי שהיא."),
            )
            StopNamesRow(ctx, explained = false)
            Sub(T("Time format", "תבנית שעה"))
            ClockChoice(ctx)
        }
    }

    part(
        "look",
        T("Look and colour", "מראה וצבע"),
        listOfNotNull(
            when (K.look) { Look.OLED -> "OLED"; Look.LIGHT -> T("Light", "בהיר"); Look.DARK -> T("Dark", "כהה") },
            if (!liquidGlassReady) null else if (K.liquid) T("liquid glass", "זכוכית נוזלית") else T("solid", "אחיד"),
            if (MapFile.type == MapFile.Type.SATELLITE) T("satellite map", "מפת לוויין") else T("standard map", "מפה רגילה"),
        ).joinToString(" · "),
    ) {
        // In setup's order: the preview, the look and its glass, the colour, the line badges, the map.
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            AccentPreview()
            LookChoices { Sub(it) }
            Sub(T("Colour", "צבע"))
            Spacer(Modifier.height(K.gap4))
            AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
            LineBadgeChoice()
            MapChoices()
        }
    }

    part(
        "updates",
        T("Updates", "עדכונים"),
        T.ltr("Kav ${Updates.installedVersion(ctx)}") +
            (model.update?.let { T(" · ${it.version} is available", " · גרסה ${it.version} זמינה") } ?: ""),
    ) {
        Spacer(Modifier.height(K.gap1))
        UpdateSection(model)
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            UpdatePrompts()
            AutomaticUpdates()
        }
    }

    part(
        "absent",
        T("About Kav", "על Kav"), T("No ads, analytics or account", "בלי פרסומות, אנליטיקה או חשבון"),
    ) {
        Absent(
            T("No account, unless you pay", "אין חשבון, אלא אם משלמים"),
            T(
                "Planning, live times and search need no sign-in, profile or sync. Paying for a ride signs in to " +
                    "your own Rav-Pass account, used for nothing but paying.",
                "תכנון, זמנים בזמן אמת וחיפוש לא דורשים התחברות, פרופיל או סנכרון. תשלום על נסיעה מתחבר " +
                    "לחשבון רב-פס שלכם, שמשמש רק לתשלומים.",
            ),
        )
        Absent(
            T("No adverts", "אין פרסומות"),
            T(
                "Vungle, AdMob and Facebook Audience Network are all absent. Kav calls no ad endpoint, and never " +
                    "requests ad targeting.",
                "Vungle, ‏AdMob ו־Facebook Audience Network: כולם לא נמצאים כאן. Kav לא פונה לאף נקודת קצה פרסומית, " +
                    "ולעולם לא מבקשת מיקוד פרסומי.",
            ),
        )
        Absent(
            T("No analytics or attribution", "אין אנליטיקה או ייחוס"),
            T(
                "Braze (~1,100), AppsFlyer (~890), Adjust, " +
                    "Firebase Crashlytics (443) and Facebook SDK (~700) are all absent.",
                "Braze (כ־1,100), AppsFlyer (כ־890), Adjust, " +
                    "Firebase Crashlytics (443) ו־Facebook SDK (כ־700): כולם לא נמצאים כאן.",
            ),
        )
        Absent(
            T("No support chat", "אין צ'אט תמיכה"),
            T("Zendesk (~1,800 references) is not here either.", "גם Zendesk (כ־1,800 הפניות) לא נמצאת כאן."),
        )
        Absent(
            T("No upsell", "אין מכירה נוספת"),
            T(
                "There is no premium tier to be offered, so nothing in this app " +
                    "has a reason to interrupt you.",
                "אין גרסת פרימיום להציע, ולכן לשום דבר באפליקציה הזו אין סיבה להפריע לכם.",
            ),
        )
        Sub(T("Credits", "קרדיטים"))
        Absent(
            T("Maps", "מפות"),
            T(
                "Map data © OpenStreetMap contributors, Protomaps. Satellite imagery: Esri, Vantor, Earthstar Geographics.",
                "נתוני המפה © תורמי OpenStreetMap, Protomaps. צילומי הלוויין: Esri, Vantor, Earthstar Geographics.",
            ),
        )
    }

    Box(Modifier.fillMaxSize()) {
    androidx.compose.animation.AnimatedContent(
        page, Modifier.fillMaxSize().background(K.bg),
        transitionSpec = { if (targetState != null) forward() else backward() }, label = "settingsPage",
    ) { shown ->
        val open = parts.firstOrNull { it.id == shown }
        Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomBarInset.current + K.gap8)) {
            if (open == null) {
                ScreenHeader(T("Settings", "הגדרות"), "", back = onClose)
                // A new version waiting comes before everything else.
                if (waiting) {
                    Group(T("Update available", "עדכון זמין"))
                    Column(Modifier.padding(horizontal = K.gap1)) { UpdateSection(model) }
                    Spacer(Modifier.height(K.gap5))
                }
                Column(Modifier.padding(horizontal = K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap6)) {
                    for (group in SETTINGS_GROUPS) PartGroup(parts.filter { it.id in group }) { page = it }
                }
                // A few actions each, so rows here rather than pages; their headers bring their own space.
                Column(Modifier.padding(horizontal = K.gap4)) {
                    BackupSection(model)
                    HelpSection()
                    FixCheck()
                }
            } else {
                ScreenHeader(open.title, "", back = { page = null })
                Column(Modifier.padding(horizontal = K.gap1), content = open.content)
            }
        }
    }
    SeenPlacePicker(model, choosing) { choosing = false }
    }
}

// Picking the place search sees: it slides up over the page it was asked from and back down onto it, which stays.
@Composable
internal fun SeenPlacePicker(model: KavModel, open: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.animation.AnimatedVisibility(open, enter = modalIn(), exit = modalOut()) {
        var q by remember { mutableStateOf("") }
        PlacePicker(
            title = T("A place for Google to see", "מקום ש-Google יראה"),
            here = null,
            allowMyLocation = false,
            onMyLocation = {},
            onPick = { p -> model.setSeen(ctx, Seen.PLACE, p); onClose() },
            onDismiss = onClose,
            favourites = model.favourites,
            onSaveFavourites = { model.saveFavourites(ctx, it) },
            query = q,
            onQuery = { q = it },
            net = model.net,
        )
    }
}

private class SettingsPart(val id: String, val title: String, val summary: String, val content: @Composable ColumnScope.() -> Unit)

// Trips first, then how Kav looks and behaves, then Kav itself.
private val SETTINGS_GROUPS = listOf(
    listOf("live", "plans", "trip", "pay"),
    listOf("lang", "look", "privacy"),
    listOf("updates", "absent"),
)

@Composable
private fun PartGroup(parts: List<SettingsPart>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.padding(start = K.gap4).fillMaxWidth().height(0.5.dp).background(K.border))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClick = { onOpen(p.id) })
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.title, fontSize = 16.sp, color = K.text)
                    Text(p.summary, fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(K.gap2))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                    tint = K.dim, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// A heading inside a part.
@Composable
private fun Sub(title: String) = SectionHeader(title)

// Kav's GitHub forms, prefilled (Feedback.kt), the code, and the coffee.
@Composable
private fun HelpSection() {
    val ctx = LocalContext.current
    @Composable
    fun link(title: String, subtitle: String, url: () -> String) = ListRow(
        title, subtitle = subtitle, onClick = { openLink(ctx, url()) }, chevron = false,
        trailing = { Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp)) },
    )
    InfoHeader(T("Help and feedback", "עזרה ומשוב"), T(
            "The forms open with Kav's version and your phone's model filled in, for you to check. Nothing is sent without you.",
            "הטפסים נפתחים כשגרסת Kav ודגם הטלפון כבר ממולאים, כדי שתבדקו. שום דבר לא נשלח בלעדיכם.",
        ))
    ListGroup {
        row { link(T("Report a bug", "דיווח על באג"), T("On GitHub", "ב-GitHub")) { feedbackLink(ctx, bug = true) } }
        row { link(T("Ask for a feature", "בקשה לפיצ'ר"), T("On GitHub", "ב-GitHub")) { feedbackLink(ctx, bug = false) } }
        row { link(T("Kav's code", "הקוד של Kav"), T("And its server's", "ושל השרת שלה")) { REPO_URL } }
        row { link(T("Buy me a coffee", "קנו לי קפה"), T("If you like Kav", "אם Kav עוזרת לכם")) { COFFEE_URL } }
    }
}

@Composable
internal fun LookChoices(inset: Dp = 0.dp, heading: @Composable (String) -> Unit) {
    val ctx = LocalContext.current
    heading(T("Look", "מראה"))
    LookRow(inset)
    if (!liquidGlassReady) return
    InfoHeader(T("Glass", "זכוכית"), T("Liquid glass is the one we recommend.", "זכוכית נוזלית היא האפשרות המומלצת."))
    Row(Modifier.padding(horizontal = inset).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        KavSegmented(
            listOf(T("Liquid glass", "זכוכית נוזלית"), T("Solid", "אחיד")), selected = if (K.liquid) 0 else 1,
            onSelect = { i -> K.liquid = i == 0; Prefs.setLiquidGlass(ctx, i == 0) },
        )
    }
}

// setup: only whether alerts are spoken; the small window and starting at once are Settings' alone.
@Composable
internal fun OnTheWayChoices(
    spoken: Boolean, startNow: Boolean, onSpoken: (Boolean) -> Unit, onStart: (Boolean) -> Unit, setup: Boolean = false,
) {
    val ctx = LocalContext.current
    InfoHeader(T("Alerts", "התראות"), if (setup) T(
        "Your bus arriving and when to get off, spoken over whatever is playing.",
        "האוטובוס שלכם מגיע ומתי לרדת, בקול מעל מה שמתנגן.",
    ) else T(
        "Your bus arriving and when to get off, spoken over whatever is playing; and the current step in a small window when you leave Kav.",
        "האוטובוס שלכם מגיע ומתי לרדת, בקול מעל מה שמתנגן; והצעד הנוכחי בחלון קטן כשיוצאים מ-Kav.",
    ))
    ListGroup {
        row {
            ToggleRow(T("Say alerts out loud", "הקראת התראות בקול"), spoken, { SpeakerGlyph(if (spoken) K.text else K.dim, 18.dp) }) { on ->
                onSpoken(on); Prefs.setSpokenAlerts(ctx, on)
                if (on) uk.noammm.kav.Speaker.say(ctx, T("Alerts will sound like this", "כך יישמעו ההתראות"))
            }
        }
        if (!setup) row {
            ToggleRow(T("Small window", "חלון קטן"), Pip.allowed, { PipGlyph(if (Pip.allowed) K.text else K.dim) }) { on ->
                Pip.allowed = on; Prefs.setPipNavigation(ctx, on)
            }
        }
    }
    if (setup) return
    InfoHeader(T("Starting a trip", "התחלת נסיעה"), T(
        "A trip you start goes straight to its first walk, as in other navigation apps, instead of waiting for its time or for you to move away.",
        "נסיעה שמתחילים עוברת מיד להליכה הראשונה, כמו באפליקציות ניווט אחרות, במקום לחכות לשעה שלה או לכך שתתרחקו.",
    ))
    ListGroup {
        row {
            ToggleRow(T("Start right away", "להתחיל מיד"), startNow, { RouteGlyph(if (startNow) K.text else K.dim) }) { on ->
                onStart(on); Prefs.setBeginTripsImmediately(ctx, on)
            }
        }
    }
}

// How bus lines look: in Look and colour, and the colour step of setting Kav up.
@Composable
internal fun LineBadgeChoice() {
    val ctx = LocalContext.current
    InfoHeader(
        T("Line badges", "סמלי הקווים"),
        T(
            "How bus lines look on boards, plans and trips. Kav: the operator's colour fills the badge. Strip: a grey badge with the operator's colour as a strip under it.",
            "איך קווי האוטובוס נראים בלוחות, במסלולים ובנסיעות. Kav: צבע המפעיל ממלא את הסמל. פס: סמל אפור עם פס בצבע המפעיל מתחתיו.",
        ),
    )
    val badges = listOf(LineBadgeAppearance.KAV, LineBadgeAppearance.STRIP)
    KavSegmented(
        listOf("Kav", T("Strip", "פס")), badges.indexOf(K.lineBadgeAppearance),
        { i -> K.lineBadgeAppearance = badges[i]; Prefs.setLineBadges(ctx, badges[i]) },
    )
    androidx.compose.animation.AnimatedContent(
        K.lineBadgeAppearance, Modifier.fillMaxWidth().padding(top = K.gap3),
        transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(220, delayMillis = 60)) +
                androidx.compose.animation.scaleIn(tween(220, delayMillis = 60), initialScale = .9f)) togetherWith
                androidx.compose.animation.fadeOut(tween(150))
        },
        label = "badgeLook",
    ) { look ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap3, Alignment.CenterHorizontally)) {
            for ((number, operator) in listOf("480" to 3, "69" to 5, "1" to 15)) {
                LinePlateBadge(number, Mode.BUS, operatorPlate(Operators.ofGtfs(operator)), look = look)
            }
        }
    }
}

// Whether Kav tells of new versions on its own: in Updates, under its Check for updates, and the updates step of
// setting Kav up, which has none.
@Composable
internal fun UpdatePrompts(checkAbove: Boolean = true) {
    val ctx = LocalContext.current
    InfoHeader(T("New versions", "גרסאות חדשות"), T(
        "Off, Kav doesn't look for new versions on its own and shows no popup or dot for them, for updating with " +
            "Obtainium or another app." + if (checkAbove) " Check for updates above still works." else "",
        "כשכבוי, Kav לא מחפשת גרסאות חדשות בעצמה ולא מציגה עליהן חלון או נקודה, בשביל עדכון עם Obtainium " +
            "או אפליקציה אחרת." + if (checkAbove) " הכפתור בדקו עדכונים למעלה עדיין עובד." else "",
    ))
    ListGroup {
        row {
            ToggleRow(
                T("Tell me about new versions", "עדכנו אותי על גרסאות חדשות"), Shown.updatePrompts,
                { Icon(Icons.Rounded.NewReleases, null, tint = if (Shown.updatePrompts) K.text else K.dim, modifier = Modifier.size(22.dp)) },
            ) { on -> Shown.updatePrompts = on; Prefs.setUpdatePrompts(ctx, on) }
        }
    }
}

// Kav's own map, or satellite photos, which load online.
@Composable
internal fun MapChoices() {
    val ctx = LocalContext.current
    val types = listOf(MapFile.Type.STANDARD, MapFile.Type.SATELLITE)
    InfoHeader(
        T("Map", "מפה"),
        T("Standard works without internet. Satellite photos come from Esri while you look, so Esri sees which area you view.",
            "המפה הרגילה עובדת בלי אינטרנט. תמונות הלוויין מגיעות מ-Esri בזמן הצפייה, ולכן Esri רואה איזה אזור אתם צופים בו."),
    )
    KavSegmented(
        listOf(T("Standard", "רגילה"), T("Satellite", "לוויין")), selected = types.indexOf(MapFile.type).coerceAtLeast(0),
        onSelect = { i -> MapFile.type = types[i]; Prefs.setMapType(ctx, types[i]) },
    )
    // The map as it looks with the choice above, around Dizengoff Center; it only shows, so the page scrolls over it.
    // Before the map is downloaded (setup), it's read from GitHub for this spot alone.
    Box(Modifier.padding(top = K.gap3).fillMaxWidth().height(160.dp).panel(K.rCard)) {
        TileMap(listOf(32.0731 to 34.7712, 32.0781 to 34.7792), Modifier.fillMaxSize(), remote = true)
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

@Composable
internal fun PlanChoices(model: KavModel) {
    val ctx = LocalContext.current
    val offered = filtersOf(KavPlan.source)
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
        InfoHeader(
            T("What a plan may show", "מה מסלול יכול לכלול"),
            offered.joinToString("\n") { f -> f.label + ": " + f.desc },
        )
        ListGroup {
            offered.forEach { f ->
                row {
                    val on = f in model.filters
                    ToggleRow(f.label, on, { FilterGlyph(f, on) }) { model.setFilter(ctx, f, it) }
                }
            }
        }
    }
}

@Composable
internal fun LiveSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Live locations and arrival times", "מיקומים וזמני הגעה בזמן אמת"),
        { s ->
            if (s.hopOn) T(
                "${sourceName(s)} supplies arrivals and vehicle positions. The map shows vehicles serving nearby stops, not every vehicle in the area. Requests go directly to HopOn, which sees your IP address and the requested area or stops.",
                "${sourceName(s)} מספק זמני הגעה ומיקומי כלי רכב. המפה מציגה כלי רכב המשרתים תחנות סמוכות, ולא את כל כלי הרכב באזור. הבקשות נשלחות ישירות להופאון, שרואה את כתובת ה-IP ואת האזור או התחנות המבוקשים.",
            ) else when (s) {
                Source.BUS_NEARBY, Source.EGG, Source.MOOVIT -> ""
                Source.KAV -> T("Buses and trains straight from the Ministry of Transport, with Kav's own arrival times. ",
                    "אוטובוסים ורכבות ישירות ממשרד התחבורה, עם זמני ההגעה של Kav. ")
                Source.OWN -> T("Your own copy of Kav's server, from server/selfhost: nothing goes to Kav's. ",
                    "עותק משלכם של השרת של Kav, מ-server/selfhost: שום דבר לא נשלח לשרת של Kav. ")
            } + if (s.hopOn) "" else T(
                "A server sees only the lines and area you look at, never your IP address, and keeps no logs.",
                "השרת רואה רק את הקווים והאזור שאתם צופים בהם, אף פעם לא את כתובת ה-IP שלכם, ולא שומר יומנים.",
            )
        },
        Source.LIVE, KavLive.source, { s -> KavLive.source = s; Prefs.setLiveSource(ctx, s) },
        // Own picked but not set up yet: its address and key come first, Kav's server answers until then.
        server = KavLive.server.takeUnless { KavLive.source == Source.OWN && KavLive.ownServer.isEmpty() },
        fields = true,
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to T("Checking the connection", "בודקים את החיבור")
            answers?.live == true && KavLive.source.hopOn -> K.realtime to T("Connected · nearby-stop coverage", "מחובר · כיסוי לפי תחנות סמוכות")
            answers?.live == true -> K.realtime to T(
                "Connected · ${"%,d".format(answers.vehicles)} buses live",
                "מחובר · ${"%,d".format(answers.vehicles)} אוטובוסים בזמן אמת",
            )
            answers != null -> K.problem to T("Connected, but no live buses yet", "מחובר, אבל עוד אין אוטובוסים בזמן אמת")
            else -> K.problem to if (KavLive.source.hopOn) T("Provider unavailable. Try again later.", "הספק לא זמין. נסו שוב מאוחר יותר.")
                else T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
        }
    }
}

// Who plans trips: Moovit's website by default. The rider's own server plans once it's set up, and Kav's until then; one
// that lists no planner plans nothing, and says so.
@Composable
internal fun PlanningSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Trip planning", "תכנון מסלולים"),
        { s ->
            when (s) {
                Source.BUS_NEARBY, Source.EGG -> T(
                    "${sourceName(s)} plans trips through HopOn. The provider receives the trip's points, time and your IP address. Last-trip searches arrive by 02:30, as in its app. Taxi cards are available with Kav's planner.",
                    "${sourceName(s)} מתכנן מסלולים דרך הופאון. הספק מקבל את נקודות המסלול, השעה וכתובת ה-IP. חיפוש נסיעה אחרונה הוא להגעה עד 02:30, כמו ביישומון שלו. כרטיסי מוניות זמינים בתכנון של Kav.",
                )
                Source.KAV -> T(
                    "Kav's planner works out the trips you search for on Kav's server, on the ministry's timetable. It sees each trip's two points and time, never your IP address, and keeps no logs.",
                    "המתכנן של Kav מחשב את המסלולים שאתם מחפשים בשרת של Kav, לפי לוח הזמנים של משרד התחבורה. הוא רואה את שתי הנקודות והשעה של כל מסלול, אף פעם לא את כתובת ה-IP שלכם, ולא שומר יומנים.",
                )
                Source.OWN -> T("Your own copy of Kav's server works out the trips you search for, with its planner on: nothing goes to Kav's.",
                    "עותק משלכם של השרת של Kav מחשב את המסלולים שאתם מחפשים, עם המתכנן שלו: שום דבר לא נשלח לשרת של Kav.")
                Source.MOOVIT -> T(
                    "Moovit plans the trips you search for on its public website, which Kav opens unseen and logged out, as a browser would. Moovit receives each trip's two points and time, and your IP address, also when Kav checks that its site is up while Kav is open. It can't plan through stops on the way.",
                    "Moovit מתכנן את המסלולים שאתם מחפשים באתר הציבורי שלו, ש-Kav פותח ברקע וללא התחברות, כמו דפדפן. Moovit מקבל את שתי הנקודות והשעה של כל מסלול ואת כתובת ה-IP, גם כש-Kav בודק שהאתר שלו זמין בזמן ש-Kav פתוח. הוא לא מתכנן דרך עצירות בדרך.",
                )
            } + T(
                " The trips it finds get their live times from the choice above.",
                " המסלולים שהוא מוצא מקבלים את הזמנים בזמן אמת מהבחירה שלמעלה.",
            )
        },
        Source.PLANNING, KavPlan.source, { s -> KavPlan.source = s; Prefs.setPlanSource(ctx, s) },
        // Own picked but not set up yet: its address and key come first, Kav's server plans until then.
        server = KavPlan.server.takeUnless { KavPlan.source == Source.OWN && KavLive.ownServer.isEmpty() },
        // Set up above already when live locations come from it too.
        fields = KavLive.source != Source.OWN,
        checked = { at, c ->
            KavPlan.up = c is KavLive.Check.Answers
            KavPlan.offered = at == KavLive.KAV_SERVER || (c as? KavLive.Check.Answers)?.planner != null
        },
        note = when {
            KavPlan.source == Source.OWN && KavLive.ownServer.isEmpty() ->
                if (KavLive.source == Source.OWN) T("Set up your server above, under live locations. Until then, Kav's server plans trips.",
                    "הגדירו את השרת שלכם למעלה, תחת מיקומים בזמן אמת. עד אז, השרת של Kav מתכנן מסלולים.")
                else T("Until your server is set up, Kav's server plans trips.", "עד שהשרת שלכם מוגדר, השרת של Kav מתכנן מסלולים.")
            !KavPlan.offered && KavPlan.source == Source.OWN ->
                T("Your server doesn't plan trips yet. Turn its planner on, or pick Kav's server.",
                    "השרת שלכם עוד לא מתכנן מסלולים. הפעילו את המתכנן שלו, או בחרו בשרת של Kav.")
            else -> null
        },
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to T("Checking the connection", "בודקים את החיבור")
            answers != null && (KavPlan.server == KavLive.KAV_SERVER || answers.planner == true) ->
                K.realtime to T("Connected · trip planner ready", "מחובר · מתכנן המסלולים מוכן")
            answers?.planner == false -> K.problem to T("Connected, but its trip planner isn't ready yet", "מחובר, אבל מתכנן המסלולים שלו עוד לא מוכן")
            answers != null -> K.problem to T("Connected, but it doesn't plan trips", "מחובר, אבל הוא לא מתכנן מסלולים")
            else -> K.problem to if (KavPlan.source.provider) T("Provider unavailable. Try again later.", "הספק לא זמין. נסו שוב מאוחר יותר.")
                else T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
        }
    }
}

// Where the Lines tab, a line's stops and times, and stop boards come from: the week on the phone, Kav's (built on GitHub)
// or the rider's own server's, or a provider asked online as each opens, with the week on the phone standing in while it
// can't be reached.
@Composable
internal fun TimetableSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Timetable", "לוח זמנים"),
        { s ->
            when (s) {
                Source.BUS_NEARBY, Source.EGG -> T(
                    "${sourceName(s)} gives the Lines tab's lines, each line's stops and times, and each stop's departures, asked through HopOn as you open them. HopOn sees the lines and stops you open, and your IP address.",
                    "${sourceName(s)} נותן את רשימת הקווים, את התחנות והזמנים של כל קו ואת היציאות מכל תחנה, דרך הופאון, כשפותחים אותם. הופאון רואה את הקווים והתחנות שאתם פותחים ואת כתובת ה-IP שלכם.",
                )
                Source.MOOVIT -> T(
                    "Moovit's public website gives the Lines tab's lines and each line's stops and times, asked as you open them in the same unseen, logged-out page Kav plans with. Stop boards combine its line schedules, using the phone's timetable to find the lines to ask for. Moovit sees the lines you open, and your IP address.",
                    "האתר הציבורי של Moovit נותן את רשימת הקווים ואת התחנות והזמנים של כל קו, כשפותחים אותם, באותו דף נסתר וללא התחברות ש-Kav מתכננת בו מסלולים. לוחות התחנות משלבים את לוחות הקווים שלו, ולוח הזמנים שבטלפון עוזר למצוא אילו קווים לבקש. Moovit רואה את הקווים שאתם פותחים ואת כתובת ה-IP שלכם.",
                )
                Source.KAV -> T(
                    "The week of scheduled departures on your phone, built each night on GitHub from the Ministry of Transport's timetable. Lines and stop boards open without signal and ask no one.",
                    "שבוע היציאות המתוכננות שבטלפון, שנבנה כל לילה ב-GitHub מלוח הזמנים של משרד התחבורה. קווים ולוחות תחנות נפתחים בלי קליטה ולא שואלים אף אחד.",
                )
                Source.OWN -> T(
                    "The week your own copy of Kav's server builds each night, kept on your phone: nothing goes to Kav's.",
                    "השבוע שעותק משלכם של השרת של Kav בונה כל לילה, שמור בטלפון: שום דבר לא נשלח לשרת של Kav.",
                )
            } + if (s.provider) T(" Without signal, Kav shows the timetable on your phone instead.", " בלי קליטה, Kav מציגה במקום זאת את לוח הזמנים שבטלפון.") else ""
        },
        Source.TIMETABLE, Schedules.source, { s -> Schedules.source = s; Prefs.setTimetableSource(ctx, s) },
        // The week on the phone asks no one until it's updated (below); the own server once it's set up.
        server = when {
            Schedules.source.provider -> KavLive.serverOf(Schedules.source)
            Schedules.source == Source.OWN -> KavLive.ownServer.ifEmpty { null }
            else -> null
        },
        // Set up above already when live locations or trip planning come from it too.
        fields = KavLive.source != Source.OWN && KavPlan.source != Source.OWN,
        note = if (Schedules.source == Source.OWN && KavLive.ownServer.isEmpty() && (KavLive.source == Source.OWN || KavPlan.source == Source.OWN)) {
            T("Set up your server above. Until then, the week comes from GitHub.", "הגדירו את השרת שלכם למעלה. עד אז, השבוע מגיע מ-GitHub.")
        } else null,
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to T("Checking the connection", "בודקים את החיבור")
            answers != null && Schedules.source.provider -> K.realtime to T("Connected · timetable ready", "מחובר · לוח הזמנים זמין")
            answers?.timetable?.isNotEmpty() == true -> K.realtime to T("Connected · its week is built", "מחובר · השבוע שלו בנוי")
            answers != null -> K.problem to T("Connected, but its week isn't built yet", "מחובר, אבל השבוע שלו עוד לא נבנה")
            else -> K.problem to if (Schedules.source.provider) T("Provider unavailable. Try again later.", "הספק לא זמין. נסו שוב מאוחר יותר.")
                else T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
        }
    }
}

internal fun sourceName(source: Source): String = when (source) {
    Source.KAV -> "Kav"
    Source.OWN -> T("My own", "שרת משלי")
    Source.BUS_NEARBY -> T("Bus Nearby", "אוטובוס קרוב")
    Source.EGG -> "EGG"
    Source.MOOVIT -> "Moovit"
}

// A null server means the own server isn't configured; a null status means its check is pending.
// Address and key fields appear only under the first choice using the own server.
@Composable
private fun SourceChoice(
    title: String, about: (Source) -> String, order: List<Source>, source: Source, onSource: (Source) -> Unit, server: String?,
    fields: Boolean, checked: (String, KavLive.Check) -> Unit = { _, _ -> }, note: String? = null,
    status: (KavLive.Check?) -> Pair<Color, String>,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(KavLive.ownServer.removePrefix("https://")) }
    var key by remember { mutableStateOf(KavLive.ownKey) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KavLive.Check?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The server in use, checked again whenever it changes.
    var inUse by remember { mutableStateOf<KavLive.Check?>(null) }
    LaunchedEffect(server, source) {
        inUse = null
        val at = server ?: return@LaunchedEffect
        val c = withContext(Dispatchers.IO) { KavLive.check(at, KavLive.keyFor(at), source) }
        inUse = c
        checked(at, c)
    }
    // A check of another address under way, called off when another starts.
    var check by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun test(at: String, with: String) {
        check?.cancel()
        problem = null
        check = scope.launch {
            checking = true; result = null
            result = withContext(Dispatchers.IO) { KavLive.check(at, with) }
            checking = false
        }
    }
    // A change of server or choice that moves where the daily timetable comes from asks the new one at once.
    fun moving(change: () -> Unit) {
        val before = KavLive.timetableServer
        change()
        if (KavLive.timetableServer != before) scope.launch(Dispatchers.IO) { runCatching { uk.noammm.kav.data.Timetable.serverChanged(app) } }
    }
    fun use(at: String, with: String, bundleKey: String) = moving {
        KavLive.ownServer = at; KavLive.ownKey = with
        Prefs.setOwnServer(ctx, at, with, bundleKey); KavLive.recheck.trySend(Unit)
        result = null
    }
    Column(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
    InfoHeader(title, about(source))
    // In the choice's own order (Source.LIVE, Source.PLANNING): its default first.
    KavSegmented(
        order.map(::sourceName), order.indexOf(source),
        { i -> moving { onSource(order[i]) }; KavLive.recheck.trySend(Unit) },
    )
    var said by remember { mutableStateOf(note) }
    if (note != null) said = note
    AnimatedVisibility(
        note != null,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) { NoteCard(said.orEmpty(), Modifier.padding(top = K.gap2)) }
    val settingUp = server == null
    val showFields = source == Source.OWN && fields
    val (dot, line) = status(inUse)
    val title = if (source.provider) sourceName(source) else if (server != KavLive.KAV_SERVER) T.ltr(server.orEmpty().removePrefix("https://")) else T("Kav's server", "השרת של Kav")
    // The card comes and goes with a choice that asks a server, and what it says (another provider, its check coming
    // back) fades from one to the next.
    AnimatedVisibility(
        !settingUp || showFields,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) {
    Column {
    Spacer(Modifier.height(K.gap3))
    Column(Modifier.fillMaxWidth().panel(K.rCard).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        if (!settingUp) {
            val shade by androidx.compose.animation.animateColorAsState(dot, tween(250), label = "statusDot")
            androidx.compose.animation.AnimatedContent(
                title to line,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(androidx.compose.animation.SizeTransform(clip = false))
                },
                label = "sourceStatus",
            ) { (name, said) ->
                ListRow(
                    name, subtitle = said,
                    leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(shade)) },
                    onClick = if (source.provider) null else ({ open = !open }),
                )
            }
        }
        if (!settingUp && open && !source.provider) inUse?.let {
            Separator()
            Column(Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, bottom = K.gap4)) { CheckLines(it) }
        }
        if (showFields) {
            if (!settingUp) Separator()
            Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                KavField(address, { address = it; result = null; problem = null }, "kav.example.com", Modifier.fillMaxWidth(),
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                KavField(key, { key = it; result = null; problem = null },
                    T("Its key (KAV_APP_KEY in its .env)", "המפתח שלו (KAV_APP_KEY בקובץ ה-‎.env)"),
                    Modifier.fillMaxWidth(), keyboard = androidx.compose.ui.text.input.KeyboardType.Password)
                problem?.let { Text(it, style = K.type.subhead, color = K.problem) }
                Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                    KavButton(T("Test connection", "בדיקת חיבור"), {
                        if (!checking) {
                            val at = KavLive.serverAddress(address)
                            if (at == null) problem = T("That isn't an https address.", "זו לא כתובת https.")
                            else test(at, key.trim())
                        }
                    }, style = ButtonStyle.Secondary, busy = checking)
                    val ready = result as? KavLive.Check.Answers
                    if (ready != null) KavButton(T("Use this server", "שימוש בשרת הזה"), {
                        KavLive.serverAddress(address)?.let { use(it, key.trim(), ready.bundleKey.orEmpty()) }
                    })
                }
                result?.let { CheckLines(it) }
            }
        }
    }
    }
    }
    }
}

// What a test found, a line for each part of the server, as a rider would miss it.
@Composable
private fun CheckLines(c: KavLive.Check) {
    val lines: List<Pair<Boolean, String>> = when (c) {
        is KavLive.Check.Unreached -> listOf(false to when (c.why) {
            KavLive.Check.Why.NO_SUCH_HOST -> T("No server found at that address.", "לא נמצא שרת בכתובת הזו.")
            KavLive.Check.Why.HTTPS -> T("Its HTTPS certificate isn't valid, so Kav won't use it.", "תעודת ה-HTTPS שלו לא תקינה, ולכן Kav לא תשתמש בו.")
            KavLive.Check.Why.TIMEOUT -> T("It didn't answer in time.", "הוא לא ענה בזמן.")
            KavLive.Check.Why.NO_ANSWER -> T("Nothing answers at that address.", "שום דבר לא עונה בכתובת הזו.")
        })
        KavLive.Check.Refused -> listOf(false to T(
            "It answered, but not to this key: it should be the KAV_APP_KEY in the server's .env.",
            "הוא ענה, אבל לא למפתח הזה: הוא צריך להיות ה-KAV_APP_KEY שבקובץ ה-‎.env של השרת.",
        ))
        KavLive.Check.NotKav -> listOf(false to T("Something answers there, but not Kav's server.", "משהו עונה שם, אבל לא השרת של Kav."))
        is KavLive.Check.Answers -> listOfNotNull(
            if (c.live) true to T("Live buses: ${"%,d".format(c.vehicles)} reporting now", "אוטובוסים בזמן אמת: ${"%,d".format(c.vehicles)} מדווחים עכשיו")
            else false to T(
                "No live buses yet: the Ministry of Transport isn't answering it. Check MOT_KEY.",
                "עוד אין אוטובוסים בזמן אמת: משרד התחבורה לא עונה לו. בדקו את MOT_KEY.",
            ),
            c.planner?.let {
                if (it) true to T("Trip planner: ready", "מתכנן המסלולים: מוכן")
                else false to T(
                    "Trip planner: not ready yet. Its first build takes about half an hour.",
                    "מתכנן המסלולים: עוד לא מוכן. הבנייה הראשונה שלו לוקחת בערך חצי שעה.",
                )
            },
            c.predictions?.let {
                if (it) true to T("Kav's predicted times: on", "הזמנים החזויים של Kav: פעילים")
                else false to T("Kav's predicted times: off, so the ministry's are shown", "הזמנים החזויים של Kav: כבויים, ולכן מוצגים אלה של משרד התחבורה")
            },
            c.timetable?.let { from ->
                val day = runCatching {
                    java.time.LocalDate.parse(from).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", T.locale))
                }.getOrNull()
                if (day != null) true to T("Today's timetable: from $day", "לוח הזמנים של היום: מ-$day")
                else false to T("Today's timetable: not built yet", "לוח הזמנים של היום: עוד לא נבנה")
            },
        )
    }
    Column(Modifier.padding(top = K.gap3), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((ok, text) in lines) Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (ok) K.realtime else K.problem))
            Text(text, style = K.type.subhead, color = if (ok) K.text else K.textSecondary)
        }
    }
}

// The week of scheduled departures kept on the phone: updated daily from GitHub's nightly build, or the rider's own server
// when a choice uses it (Sources.timetableFrom). The last copy stays when updates are off.
@Composable
internal fun TimetableUpdates() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.timetableUpdates(ctx)) }
    InfoHeader(T("Timetable on your phone", "לוח הזמנים בטלפון"), T(
        "The week of scheduled departures Kav keeps on your phone, for stop boards and planning without signal, built each night on GitHub from the Ministry of Transport's timetable. Off, Kav keeps the last copy, which can go out of date. On mobile data, it updates only once the copy is three days old.",
        "שבוע היציאות המתוכננות ש-Kav שומרת בטלפון, ללוחות התחנות ולתכנון בלי קליטה, שנבנה כל לילה ב-GitHub מלוח הזמנים של משרד התחבורה. כשכבוי, Kav שומרת את העותק האחרון, שעלול להתיישן. בנתונים סלולריים הוא מתעדכן רק כשהעותק בן שלושה ימים.",
    ))
    ListGroup {
        row {
            ListRow(
                T("Update it every day", "לעדכן אותו כל יום"),
                Modifier.semantics { toggleableState = if (on) ToggleableState.On else ToggleableState.Off },
                subtitle = if (KavLive.timetableServer != null) T("From your server, on Wi-Fi", "מהשרת שלכם, ב-Wi-Fi")
                    else T("From GitHub, on Wi-Fi", "מ-GitHub, ב-Wi-Fi"),
                trailing = { KavSwitch(on) }, onClick = { on = !on; Prefs.setTimetableUpdates(ctx, on) }, chevron = false,
            )
        }
    }
}

// The section's summary line: where each part comes from.
@Composable
private fun serverUses(): String {
    val ctx = LocalContext.current
    fun named(at: String?) = when (at) {
        null -> T("unavailable", "לא זמין")
        KavLive.KAV_SERVER -> T("Kav's server", "השרת של Kav")
        else -> T("your server", "השרת שלכם")
    }
    val live = if (KavLive.source.hopOn) sourceName(KavLive.source) else named(KavLive.server)
    val trips = if (KavPlan.source.provider) sourceName(KavPlan.source) else named(KavPlan.planner())
    val timetable = if (Schedules.source.provider) sourceName(Schedules.source)
        else if (KavLive.timetableServer != null) T("your server", "השרת שלכם") else "Kav"
    return T("Live: ", "זמן אמת: ") + live + " · " + T("trips: ", "מסלולים: ") + trips + " · " +
        T("timetable: ", "לוח זמנים: ") + timetable
}

// Private search and what Google sees instead, in Settings.
@Composable
internal fun PrivacyRows(model: KavModel, onChoose: () -> Unit) {
    val ctx = LocalContext.current
    var priv by remember(model.prefsVersion) { mutableStateOf(Prefs.privateSearch(ctx)) }
    InfoHeader(T("Search", "חיפוש"), T(
            "Your exact location stays off search. A trip still sends its two points. Street View loads from Google only when you open it: Google then sees that spot and your IP address.",
            "המיקום המדויק שלכם לא נשלח בחיפוש. מסלול עדיין שולח את שתי הנקודות שלו. Street View נטען מ-Google רק כשפותחים אותו: Google רואה אז את המקום הזה ואת כתובת ה-IP שלכם.",
        ))
    ListGroup {
        row {
            ToggleRow(T("Private search", "חיפוש פרטי"), priv, { ShieldGlyph(if (priv) K.text else K.dim, 18.dp) }) { on ->
                priv = on; uk.noammm.kav.Prefs.setPrivateSearch(ctx, on); uk.noammm.kav.data.GoogleMaps.shareLocation = !on
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        priv,
        enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
            androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
        exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
            androidx.compose.animation.fadeOut(tween(120)),
    ) { SeenChoice(model, onChoose) }
}

// A row that turns something on or off: tapping anywhere on it flips the switch at its end.
@Composable
internal fun ToggleRow(
    title: String, on: Boolean, glyph: @Composable () -> Unit, subtitle: String? = null, onChange: (Boolean) -> Unit,
) = ListRow(
    title, subtitle = subtitle,
    modifier = Modifier.semantics { toggleableState = if (on) ToggleableState.On else ToggleableState.Off },
    leading = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { glyph() } },
    trailing = { KavSwitch(on) }, onClick = { onChange(!on) }, chevron = false,
)

// A way of getting there, by its mode's own mark.
@Composable
private fun FilterGlyph(f: ResultFilter, on: Boolean) {
    val tint = if (on) K.text else K.dim
    when (f) {
        ResultFilter.BUS -> ModeGlyph(Mode.BUS, tint, 18.dp)
        ResultFilter.TRAIN -> ModeGlyph(Mode.TRAIN, tint, 18.dp)
        ResultFilter.LIGHT_RAIL -> ModeGlyph(Mode.TRAM, tint, 18.dp)
        ResultFilter.CARMELIT_RAKAVLIT -> CarmelitMark(18.dp)
        ResultFilter.SHUTTLE -> ShuttleMark(18.dp)
        ResultFilter.TAXI -> ModeGlyph(Mode.TAXI, tint, 18.dp)
        ResultFilter.BIKE -> BikeGlyph(tint)
        ResultFilter.WALK -> WalkGlyph(tint, 16.dp)
    }
}

// What Google's search is told about where you are: one choice of three, what it means under it, and the place when
// it's one.
@Composable
private fun SeenChoice(model: KavModel, onChoose: () -> Unit) {
    val ctx = LocalContext.current
    var city by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model.seen, model.here) {
        if (model.seen != Seen.CITY) return@LaunchedEffect
        runCatching { uk.noammm.kav.loadNet(ctx) }
        city = withContext(Dispatchers.Default) { model.cityAround(ctx.applicationContext)?.name }
    }
    val seens = listOf(Seen.CITY, Seen.PLACE, Seen.NONE)
    Column {
        SectionHeader(T("What Google sees", "מה Google רואה"))
        KavSegmented(
            listOf(T("Your town", "היישוב שלכם"), T("A place", "מקום"), T("Nothing", "כלום")),
            seens.indexOf(model.seen).coerceAtLeast(0),
            { i ->
                val seen = seens[i]
                if (seen == Seen.PLACE && model.seenPlace == null) onChoose() else model.setSeen(ctx, seen)
            },
        )
        val place = model.seenPlace?.name
        androidx.compose.animation.AnimatedContent(
            model.seen to city,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "seen",
        ) { (seen, town) ->
            NoteCard(
                when (seen) {
                    Seen.NONE -> T("Searches carry no location, so results aren't sorted by distance.",
                        "החיפוש לא כולל מיקום, ולכן התוצאות לא ממוינות לפי מרחק.")
                    Seen.CITY -> T("The centre of your town, worked out on your phone. ", "מרכז היישוב שלכם, שמחושב בטלפון. ") +
                        (town?.let { T("Now: $it.", "כרגע: $it.") } ?: "")
                    Seen.PLACE -> T("A place you pick, so results near it come first.", "מקום שתבחרו, כך שתוצאות קרובות אליו מופיעות ראשונות.")
                },
                Modifier.padding(top = K.gap2),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            model.seen == Seen.PLACE,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
            ListGroup {
                row {
                    ListRow(
                        place ?: T("Pick a place", "בחירת מקום"),
                        subtitle = T("Google sees this instead of you", "Google רואה אותו במקומכם"),
                        leading = { Icon(Icons.Rounded.Place, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(24.dp)) },
                        onClick = onChoose,
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(ctx: android.content.Context) {
    KavSegmented(
        Lang.entries.map { it.label }, selected = Lang.entries.indexOf(T.lang),
        onSelect = { i -> val l = Lang.entries[i]; T.switchTo(l); Prefs.setLang(ctx, l) },
    )
}

// The language of stops', stations', towns' and lines' ends' names, apart from Kav's own, and nothing else: as the
// Ministry of Transport names them in Hebrew, or in English where it has their English names.
@Composable
internal fun StopNamesRow(ctx: android.content.Context, modifier: Modifier = Modifier, explained: Boolean = true) {
    Column(modifier) {
        // In the order Kav's languages are listed in.
        KavSegmented(
            listOf("English", "עברית"), selected = if (StopNames.english) 0 else 1,
            onSelect = { i -> StopNames.english = i == 0; Prefs.setStopNamesEnglish(ctx, i == 0) },
        )
        if (explained) NoteCard(
            T(
                "Only the names of stops, stations and towns, as the Ministry of Transport gives them, on the boards, " +
                    "lines and trips. Kav's own language stays as it is.",
                "רק שמות התחנות והיישובים, כפי שמשרד התחבורה נותן אותם, בלוחות, בקווים ובנסיעות. השפה של Kav נשארת כפי שהיא.",
            ),
            Modifier.padding(top = K.gap2),
        )
    }
}

@Composable
private fun BackupSection(model: KavModel) {
    val ctx = LocalContext.current
    fun toast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()

    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            Backup.write(ctx, uri)
                .onSuccess { toast(T("Backup saved", "הגיבוי נשמר")) }
                .onFailure { toast(T("Couldn't write that file", "לא ניתן היה לכתוב את הקובץ")) }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PendingBackup.uri = uri
    }

    InfoHeader(T("Backup", "גיבוי"), T(
            "Your saved places, trips, searches and settings. Importing replaces what is here.",
            "המקומות השמורים, הנסיעות, החיפושים וההגדרות שלכם. ייבוא מחליף את מה שנמצא כאן.",
        ))
    ListGroup {
        row { ListRow(T("Export to a .kav file", "ייצוא לקובץ ‎.kav"), onClick = { save.launch(Backup.suggestedName()) }) }
        row { ListRow(T("Import from a .kav file", "ייבוא מקובץ ‎.kav"), onClick = { open.launch(arrayOf("*/*")) }) }
    }
}

internal fun importError(e: Throwable): String =
    if (e is Backup.NotABackup) T("That file isn't a Kav backup.", "הקובץ הזה אינו גיבוי של Kav.")
    else T("Couldn't read that file.", "לא ניתן היה לקרוא את הקובץ.")

internal const val COFFEE_URL = "https://www.buymeacoffee.com/Noamm"
internal const val REPO_URL = "https://github.com/${Updates.OWNER}/${Updates.REPO}"

internal fun openLink(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// Google's panorama of a spot, in the Maps app when it's there, else on the web. Only when asked: Google learns the spot.
internal fun openStreetView(ctx: android.content.Context, look: StreetLook) {
    val (app, web) = streetViewLinks(look)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(app))
        .setPackage("com.google.android.apps.maps").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }.onFailure { openLink(ctx, web) }
}

// The Maps app's link and the web's, facing the way the look faces when it has one (degrees clockwise from north, as
// both take it: the app as cbp's second field). The web link names the spot as its query too, which Google ignores
// there, so a maps app that takes Google's links instead (Vela) opens the spot rather than nothing.
internal fun streetViewLinks(look: StreetLook): Pair<String, String> {
    val (lat, lon) = look.at
    val ll = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)
    return Pair(
        "google.streetview:cbll=$ll" + (look.heading?.let { "&cbp=0,$it,0,0,0" } ?: ""),
        "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$ll" + (look.heading?.let { "&heading=$it" } ?: "") + "&query=$ll",
    )
}

@Composable
private fun SpeakerGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        val cone = Path().apply {
            moveTo(w * .12f, h * .38f)
            lineTo(w * .30f, h * .38f)
            lineTo(w * .52f, h * .18f)
            lineTo(w * .52f, h * .82f)
            lineTo(w * .30f, h * .62f)
            lineTo(w * .12f, h * .62f)
            close()
        }
        drawPath(cone, tint, style = Stroke(sw))
        drawArc(tint, -45f, 90f, false, Offset(w * .46f, h * .32f), androidx.compose.ui.geometry.Size(w * .36f, h * .36f),
            style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(tint, -45f, 90f, false, Offset(w * .40f, h * .16f), androidx.compose.ui.geometry.Size(w * .56f, h * .68f),
            style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun RouteGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        val road = Path().apply {
            moveTo(w * .22f, h * .80f)
            cubicTo(w * .22f, h * .45f, w * .78f, h * .55f, w * .78f, h * .20f)
        }
        drawPath(road, tint, style = Stroke(sw, cap = StrokeCap.Round))
        drawCircle(tint, w * .11f, Offset(w * .22f, h * .80f))
        drawCircle(tint, w * .11f, Offset(w * .78f, h * .20f))
    }
}

@Composable
private fun PipGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        drawRoundRect(tint, Offset(w * .08f, h * .18f), androidx.compose.ui.geometry.Size(w * .84f, h * .64f),
            androidx.compose.ui.geometry.CornerRadius(w * .1f), style = Stroke(sw))
        drawRoundRect(tint, Offset(w * .48f, h * .48f), androidx.compose.ui.geometry.Size(w * .32f, h * .24f),
            androidx.compose.ui.geometry.CornerRadius(w * .05f))
    }
}

@Composable
private fun ShieldGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        val shield = Path().apply {
            moveTo(w * .5f, h * .08f)
            lineTo(w * .86f, h * .24f)
            lineTo(w * .86f, h * .52f)
            cubicTo(w * .86f, h * .78f, w * .70f, h * .90f, w * .5f, h * .96f)
            cubicTo(w * .30f, h * .90f, w * .14f, h * .78f, w * .14f, h * .52f)
            lineTo(w * .14f, h * .24f)
            close()
        }
        drawPath(shield, tint, style = Stroke(sw))
        drawCircle(tint, w * .085f, Offset(w * .5f, h * .46f))
        drawLine(tint, Offset(w * .5f, h * .46f), Offset(w * .5f, h * .66f), sw, StrokeCap.Round)
    }
}

// Kav's fixes for Google are signed files it checks once a day. This asks now, for when something has just stopped
// working and a fix may already be out.
@Composable
private fun FixCheck() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<SignedFile.Check?>(null) }
    var checking by remember { mutableStateOf(false) }
    InfoHeader(
        T("Fixes for Google", "תיקונים ל-Google"),
        T("Small signed updates Kav fetches when Google changes how it answers, so it keeps working between releases.",
            "עדכונים קטנים וחתומים ש-Kav מורידה כש-Google משנה את אופן התשובה, כדי שתמשיך לעבוד בין גרסאות."),
    )
    Row(
        Modifier.fillMaxWidth().panel(K.rCard).padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    checking -> T("Looking…", "בודקים…")
                    result == SignedFile.Check.NEWER -> T("A fix arrived and is in use", "הגיע תיקון והוא כבר בשימוש")
                    result == SignedFile.Check.SAME -> T("Kav has the latest fixes", "ל-Kav יש את התיקונים האחרונים")
                    result == SignedFile.Check.FAILED -> T("Couldn't check. Try again online.", "לא הצלחנו לבדוק. נסו שוב כשיש חיבור.")
                    else -> T("Checked once a day, no update needed", "נבדק פעם ביום, בלי צורך בעדכון")
                },
                style = K.type.body, color = if (result == SignedFile.Check.NEWER) K.realtime else K.text,
            )
        }
        KavButton(T("Check now", "בדקו עכשיו"), busy = checking, style = ButtonStyle.Secondary, onClick = {
            if (checking) return@KavButton
            scope.launch {
                checking = true
                result = withContext(Dispatchers.IO) {
                    runCatching { GoogleRecipe.refresh(ctx, force = true) }.getOrDefault(SignedFile.Check.FAILED)
                }
                checking = false
            }
        })
    }
}

@Composable
private fun Group(title: String) = SectionHeader(title, Modifier.padding(horizontal = K.gap4))

@Composable
private fun Absent(title: String, desc: String) {
    Row(
        Modifier.padding(horizontal = K.gap4, vertical = K.gap2).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Box(
            Modifier.padding(top = 11.dp).width(14.dp).height(1.dp).background(K.borderStrong),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = K.type.body)
            Text(desc, style = K.type.subhead, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// 24 or 12 hours: two choices that change every time shown, so a segmented control.
@Composable
internal fun ClockChoice(ctx: android.content.Context, modifier: Modifier = Modifier) {
    KavSegmented(
        listOf(T("24-hour (14:05)", "24 שעות (14:05)"), T("12-hour (2:05 PM)", "12 שעות (2:05 PM)")),
        selected = if (Shown.twelveHour) 1 else 0,
        onSelect = { i -> Shown.twelveHour = i == 1; Prefs.setTwelveHour(ctx, i == 1) },
        modifier = modifier,
    )
}
