package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.Updates

@Composable
fun SettingsScreen(model: KavModel, onClose: () -> Unit) {
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader(T("Your", "ההגדרות"), T("settings", "שלכם"), back = onClose)

        Group(T("language", "שפה"))
        LanguageRow(ctx)

        Group(T("colour", "צבע"))
        AccentPreview(Modifier.padding(horizontal = K.gap4))
        Spacer(Modifier.height(K.gap4))
        AccentPicker(wheel = 200.dp) { Prefs.setAccent(ctx, it.toArgb()) }

        Group(T("what a plan may show", "מה מסלול יכול לכלול"))
        FilterRows(model.filters) { f, on -> model.setFilter(ctx, f, on) }

        Group(T("updates", "עדכונים"))
        UpdateSection(model)

        Group(T("found something wrong?", "מצאתם תקלה?"))
        FeedbackRow()

        /* The point of the project, stated where it can be checked rather than
           only claimed in a README. Counts are class-path references measured in
           com.tranzmate 5.199.1.1804. */
        Group(T("what is not in here", "מה לא נמצא כאן"))
        Absent(
            T("No account", "אין חשבון"),
            T(
                "There is no sign-in, no profile, no sync. Nothing identifies you to anyone.",
                "אין התחברות, אין פרופיל, אין סנכרון. שום דבר כאן לא מזהה אתכם בפני איש.",
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

/**
 * Somewhere to send a bug or an idea that is not a comment under a forum post. The
 * tracker is the same GitHub the updates already come from, so this adds no service
 * and no account of Kav's own.
 */
@Composable
private fun FeedbackRow() {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = K.gap4)) {
        Text(
            T(
                "Bugs and ideas go to the issue tracker, where they can be answered and " +
                    "followed. Saying which phone and which Kav version helps.",
                "תקלות ורעיונות נרשמים במעקב הבאגים, שם אפשר לענות עליהם ולעקוב אחריהם. " +
                    "כדאי לציין איזה טלפון ואיזו גרסת Kav.",
            ),
            fontSize = 12.sp, color = K.dim, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(K.gap3))
        Chip(T("Report a bug", "דיווח על תקלה"), false) { openLink(ctx, "${Updates.ISSUES}/new") }
    }
}

/**
 * Kav's own words, and only those. Moovit answers in Hebrew whatever the phone is
 * set to, so following the system language left a rider whose phone is in English
 * reading Hebrew stop names inside English sentences, with no way to settle either
 * half. This is the half Kav owns, and it is switched here rather than in Android's
 * per-app language because minSdk is 26 and that is an API 33 feature.
 */
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

private fun openLink(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title, style = DisplayItalic, fontSize = 12.sp, color = K.dim,
        modifier = Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap5, bottom = K.gap2),
    )
}

/** A struck-through title: the surface exists in the app this one replaces. */
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
