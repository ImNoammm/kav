package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.PendingBackup
import uk.noammm.kav.Prefs
import uk.noammm.kav.Seen
import uk.noammm.kav.data.Backup
import uk.noammm.kav.data.Updates

@Composable
fun SettingsScreen(model: KavModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
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

    Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader(T("Your", "ההגדרות"), T("settings", "שלכם"), back = onClose)

        Group(T("time format", "תבנית שעה"))
        ClockChoice(ctx)

        if (Payer.signedIn) {
            Group(T("payments", "תשלומים"))
            PaymentsSection(model)
        }

        Group(T("language", "שפה"))
        LanguageRow(ctx)

        Group(T("colour", "צבע"))
        AccentPreview(Modifier.padding(horizontal = K.gap4))
        Spacer(Modifier.height(K.gap4))
        AccentPicker(wheel = 200.dp) { Prefs.setAccent(ctx, it.toArgb()) }
        LookChoices(inset = K.gap4) { Group(it) }

        Group(T("what a plan may show", "מה מסלול יכול לכלול"))
        FilterRows(model.filters) { f, on -> model.setFilter(ctx, f, on) }

        Group(T("what a card shows", "מה מוצג בכרטיס"))
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

        Group(T("privacy", "פרטיות"))
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            var priv by remember(model.prefsVersion) { mutableStateOf(uk.noammm.kav.Prefs.privateSearch(ctx)) }
            SwitchRow(
                T("Private search", "חיפוש פרטי"),
                T(
                    "Keep your exact location off search and off the anonymous registration Kav makes " +
                        "with Moovit. Planning a trip still sends the two points you pick, since that " +
                        "is the trip you asked it to find.",
                    "המיקום המדויק שלכם לא נשלח בחיפוש ולא ברישום האנונימי ש-Kav מבצעת מול Moovit. " +
                        "תכנון מסלול עדיין שולח את שתי הנקודות שאתם בוחרים, כי זו הנסיעה שביקשתם למצוא.",
                ),
                priv,
                { on ->
                    priv = on; uk.noammm.kav.Prefs.setPrivateSearch(ctx, on); uk.noammm.kav.data.Moovit.shareLocation = !on
                    Online.reset()
                },
            ) { ShieldGlyph(if (priv) K.text else K.dim, 18.dp) }
            if (priv) SeenChoice(model) { choosing = true }
        }

        Group(T("your data", "הנתונים שלכם"))
        BackupSection(model)

        Group(T("updates", "עדכונים"))
        UpdateSection(model)

        Group(T("what is not in here", "מה לא נמצא כאן"))
        Absent(
            T("No account, unless you pay", "אין חשבון, אלא אם משלמים"),
            T(
                "Planning, live times and search need no sign-in, profile or sync. Paying for a ride signs in to " +
                    "your own Moovit payment account, on a Moovit user Kav keeps only for paying.",
                "תכנון, זמנים בזמן אמת וחיפוש לא דורשים התחברות, פרופיל או סנכרון. תשלום על נסיעה מתחבר " +
                    "לחשבון התשלום שלכם ב-Moovit, דרך משתמש Moovit ש-Kav שומרת רק לתשלומים.",
            ),
        )
        Absent(
            T("No adverts", "אין פרסומות"),
            T(
                "The official app carries Vungle video ads (1,038 class references), " +
                    "AdMob and Facebook Audience Network. Kav calls none of the ad endpoints, and never " +
                    "requests ad targeting.",
                "האפליקציה הרשמית כוללת פרסומות וידאו של Vungle (1,038 הפניות למחלקות), " +
                    "AdMob ו־Facebook Audience Network. Kav לא פונה לאף אחת מנקודות הקצה הפרסומיות, " +
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
        Spacer(Modifier.height(K.gap8))
    }
}

@Composable
internal fun LookChoices(inset: Dp = 0.dp, heading: @Composable (String) -> Unit) {
    val ctx = LocalContext.current
    heading(T("look", "מראה"))
    Row(Modifier.padding(horizontal = inset).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        for ((look, name) in listOf(Look.OLED to "OLED", Look.LIGHT to T("Light", "בהיר"), Look.DARK to T("Dark", "כהה"))) {
            Chip(name, K.look == look) { K.applyTheme(look); Prefs.setLook(ctx, look) }
        }
    }
    if (!liquidGlassReady) return
    heading(T("glass", "זכוכית"))
    Row(Modifier.padding(horizontal = inset).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        Chip(T("Liquid glass", "זכוכית נוזלית"), K.liquid, Modifier.border(1.5.dp, K.accent, RoundedCornerShape(K.rControl))) {
            K.liquid = true; Prefs.setLiquidGlass(ctx, true)
        }
        Chip(T("Solid", "אחיד"), !K.liquid) { K.liquid = false; Prefs.setLiquidGlass(ctx, false) }
    }
    Text(
        T("Liquid glass is the one we recommend.", "זכוכית נוזלית היא האפשרות המומלצת."),
        fontSize = 11.sp, color = K.dim, modifier = Modifier.padding(horizontal = inset).padding(start = 2.dp, top = 6.dp),
    )
}

@Composable
private fun SeenChoice(model: KavModel, onChoose: () -> Unit) {
    val ctx = LocalContext.current
    var city by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model.seen, model.here) {
        if (model.seen != Seen.CITY) return@LaunchedEffect
        runCatching { uk.noammm.kav.loadNet(ctx) }
        city = withContext(Dispatchers.Default) { model.cityAround(ctx.applicationContext)?.name }
    }
    Text(
        T("what Moovit sees", "מה Moovit רואה"), style = DisplayItalic, fontSize = 12.sp, color = K.dim,
        modifier = Modifier.padding(start = K.gap1, top = K.gap4, bottom = K.gap2),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        Chip(T("Your town", "היישוב שלכם"), model.seen == Seen.CITY) { model.setSeen(ctx, Seen.CITY) }
        Chip(T("Custom location", "מיקום מותאם אישית"), model.seen == Seen.PLACE) {
            if (model.seenPlace == null) onChoose() else model.setSeen(ctx, Seen.PLACE)
        }
        Chip(T("None", "ללא"), model.seen == Seen.NONE) { model.setSeen(ctx, Seen.NONE) }
    }
    val place = model.seenPlace?.name
    Text(
        when (model.seen) {
            Seen.NONE -> T(
                "Moovit is told you're at Dizengoff Center in Tel Aviv, as before, and searches carry no " +
                    "location, so results aren't sorted by how close they are.",
                "Moovit מקבל מיקום קבוע בדיזנגוף סנטר בתל אביב, כמו קודם, והחיפוש לא כולל מיקום, " +
                    "ולכן התוצאות לא ממוינות לפי קרבה.",
            )
            Seen.CITY -> T(
                "Moovit sees the centre of the town or city you're in, or the closest one, worked out on " +
                    "your phone. Never your exact spot. " +
                    (city?.let { "Now: $it." } ?: "Kav finds your town once it has your location."),
                "Moovit רואה את מרכז היישוב שבו אתם נמצאים, או של הקרוב ביותר, שמחושב בטלפון שלכם. " +
                    "אף פעם לא את המיקום המדויק שלכם. " +
                    (city?.let { "כרגע: $it." } ?: "Kav תמצא את היישוב שלכם כשיהיה לה מיקום."),
            )
            Seen.PLACE -> if (place == null) T("Pick a place for Moovit to see.", "בחרו מקום ש-Moovit יראה.") else T(
                "Moovit sees $place instead of where you are, so places near it come first.",
                "Moovit רואה את $place ולא את המיקום שלכם, כך שמקומות קרובים אליו מופיעים ראשונים.",
            )
        },
        fontSize = 11.sp, color = K.dim, lineHeight = 15.sp,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp),
    )
    if (model.seen == Seen.PLACE) {
        Spacer(Modifier.height(K.gap2))
        Chip(T("Pick another place", "בחירת מקום אחר"), false, onClick = onChoose)
    }
}

@Composable
private fun LanguageRow(ctx: android.content.Context) {
    Row(
        Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        for (l in Lang.entries) {
            Chip(l.label, T.lang == l) { T.switchTo(l); Prefs.setLang(ctx, l) }
        }
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

    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            ActionTile(
                Modifier.weight(1f),
                T("Import", "ייבוא"),
                T("from a .kav file", "מקובץ ‎.kav"),
            ) { open.launch(arrayOf("*/*")) }
            ActionTile(
                Modifier.weight(1f),
                T("Export", "ייצוא"),
                T("to a .kav file", "לקובץ ‎.kav"),
            ) { save.launch(Backup.suggestedName()) }
        }
        Text(
            T(
                "A .kav file holds your saved places, trip history, searches and settings. " +
                    "Importing replaces what is here; the map is not part of it.",
                "קובץ ‎.kav מכיל את המקומות השמורים, היסטוריית הנסיעות, החיפושים וההגדרות שלכם. " +
                    "ייבוא מחליף את מה שנמצא כאן; המפה אינה חלק ממנו.",
            ),
            fontSize = 11.sp, color = K.dim, lineHeight = 16.sp,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

internal fun importError(e: Throwable): String =
    if (e is Backup.NotABackup) T("That file isn't a Kav backup.", "הקובץ הזה אינו גיבוי של Kav.")
    else T("Couldn't read that file.", "לא ניתן היה לקרוא את הקובץ.")

@Composable
private fun ActionTile(modifier: Modifier, label: String, sub: String, onClick: () -> Unit) {
    Column(
        modifier.panel(14.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap3, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium)
        Text(sub, fontSize = 11.sp, color = K.dim, modifier = Modifier.padding(top = 2.dp))
    }
}

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

@Composable
private fun Group(title: String) {
    Text(
        title, style = DisplayItalic, fontSize = 12.sp, color = K.dim,
        modifier = Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap5, bottom = K.gap2),
    )
}

@Composable
private fun Absent(title: String, desc: String) {
    Row(
        Modifier.padding(horizontal = K.gap4, vertical = K.gap2).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Box(
            Modifier.padding(top = 6.dp).width(14.dp).height(1.dp).background(K.borderStrong),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = K.text)
            Text(desc, fontSize = 11.sp, color = K.dim, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ClockChoice(ctx: android.content.Context) {
    var open by remember { mutableStateOf(false) }
    fun label(twelve: Boolean) = if (twelve) T("12-hour (2:05 PM)", "12 שעות (2:05 PM)") else T("24-hour (14:05)", "24 שעות (14:05)")
    Box(Modifier.padding(horizontal = K.gap4)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).panel(K.rControl)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = true }.padding(horizontal = K.gap4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(Shown.twelveHour), fontSize = 15.sp, color = K.text, modifier = Modifier.weight(1f))
            Text("▾", fontSize = 15.sp, color = K.dim)
        }
        androidx.compose.material3.DropdownMenu(
            open, onDismissRequest = { open = false },
            modifier = Modifier.background(K.bg),
            containerColor = K.bg, tonalElevation = 0.dp, shadowElevation = 8.dp,
            shape = RoundedCornerShape(K.rControl),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (K.light) K.borderStrong else Color.White),
        ) {
            listOf(false, true).forEach { twelve ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(twelve), color = if (twelve == Shown.twelveHour) K.accent else K.text) },
                    onClick = { Shown.twelveHour = twelve; Prefs.setTwelveHour(ctx, twelve); open = false },
                )
            }
        }
    }
}
