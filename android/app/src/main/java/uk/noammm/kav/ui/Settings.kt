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

@Composable
fun SettingsScreen(model: KavModel, onClose: () -> Unit) {
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader("Your", "settings", back = onClose)

        Group("colour")
        AccentPreview(Modifier.padding(horizontal = K.gap4))
        Spacer(Modifier.height(K.gap4))
        AccentPicker(wheel = 200.dp) { Prefs.setAccent(ctx, it.toArgb()) }

        Group("what a plan may show")
        FilterRows(model.filters) { f, on -> model.setFilter(ctx, f, on) }

        Group("updates")
        UpdateSection(model)

        /* The point of the project, stated where it can be checked rather than
           only claimed in a README. Counts are class-path references measured in
           com.tranzmate 5.199.1.1804. */
        Group("what is not in here")
        Absent("No account", "There is no sign-in, no profile, no sync. Nothing identifies you to anyone.")
        Absent("No adverts", "The official app carries Vungle video ads (1,038 class references), " +
            "AdMob and Facebook Audience Network. Kav calls none of the ad endpoints, and never " +
            "requests ad targeting.")
        Absent("No analytics or attribution", "Braze (~1,100), AppsFlyer (~890), Adjust, " +
            "Firebase Crashlytics (443) and Facebook SDK (~700) are all absent.")
        Absent("No support chat", "Zendesk (~1,800 references) is not here either.")
        Absent("No upsell", "There is no premium tier to be offered, so nothing in this app " +
            "has a reason to interrupt you.")
        Spacer(Modifier.height(K.gap8))
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
