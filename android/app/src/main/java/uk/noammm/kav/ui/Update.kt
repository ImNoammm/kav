package uk.noammm.kav.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.R
import uk.noammm.kav.data.Updates
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UpdatePrompt(model: KavModel) {
    val release = model.update ?: return
    if (model.updateDismissed) return
    val ctx = LocalContext.current
    // Checks off means no prompt, even for a release found before they were turned off.
    if (!Prefs.updateChecks(ctx)) return
    Dialog(onDismissRequest = { model.updateDismissed = true }) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                AppIcon(56.dp)
                Column {
                    Text("Kav", style = K.type.title)
                    Text(T.ltr("${Updates.installedVersion(ctx)} → ${release.version}"), style = K.type.subhead.tabular)
                }
            }
            ReleaseNotes(release, maxHeight = 260.dp)
            UpdateProgress(model)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(
                    T("Later", "אחר כך"), { model.updateDismissed = true }, Modifier.weight(1f).heightIn(min = 52.dp),
                    style = ButtonStyle.Secondary,
                )
                UpdateButton(model, Modifier.weight(1f)) { model.startUpdate(ctx) }
            }
        }
    }
}

@Composable
private fun AppIcon(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.ic_launcher_background), contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Kav",
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
    }
}

@Composable
private fun ReleaseNotes(release: Updates.Release, maxHeight: Dp) {
    val notes = release.notes.trim().ifBlank { release.name.ifBlank { T("No release notes.", "אין הערות גרסה.") } }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
        Separator(0.dp)
        Spacer(Modifier.height(K.gap3))
        Text(notes, style = K.type.subhead)
    }
}

@Composable
private fun UpdateProgress(model: KavModel) {
    model.updateProgress?.let { p ->
        Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
            }
            Text(
                if (p >= 1f) T("Opening the installer…", "פותחים את ההתקנה…")
                else T("Downloading… ${(p * 100).toInt()}%", "מורידים… ${(p * 100).toInt()}%"),
                fontSize = 12.sp, color = K.dim,
            )
        }
    }
    model.updateError?.let { Text(T("Could not update: $it", "העדכון נכשל: $it"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp) }
}

@Composable
private fun UpdateButton(model: KavModel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val busy = model.updateProgress != null
    KavButton(if (busy) T("Updating…", "מעדכנים…") else T("Update", "עדכון"), onClick, modifier, busy = busy)
}

@Composable
fun UpdateSection(model: KavModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val release = model.update
    var checking by remember { mutableStateOf(false) }
    // The notes of the version in hand, from the same release list the update check reads, when it's the newest.
    var installedNotes by remember { mutableStateOf<Updates.Release?>(null) }
    LaunchedEffect(release) {
        if (release != null || !Prefs.updateChecks(ctx)) return@LaunchedEffect
        installedNotes = runCatching { withContext(Dispatchers.IO) { Updates.latest() } }.getOrNull()
            ?.takeIf { it.version.removePrefix("v") == Updates.installedVersion(ctx).removePrefix("v") }
    }
    Column(
        Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(K.rCard)
            .padding(K.gap4),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            AppIcon(40.dp)
            Column(Modifier.weight(1f)) {
                Text(T.ltr("Kav ${Updates.installedVersion(ctx)}"), style = K.type.headline)
                Text(
                    when {
                        release != null -> T("${release.version} is available", "גרסה ${release.version} זמינה")
                        checking -> T("Looking…", "בודקים…")
                        model.updateFailed -> T("Couldn't check for updates", "לא הצלחנו לבדוק אם יש עדכונים")
                        model.updateChecked -> T("This is the newest release", "זו הגרסה העדכנית ביותר")
                        else -> T("Not checked yet", "עוד לא נבדק")
                    },
                    style = K.type.subhead, color = if (release != null) K.accentInk else K.textSecondary,
                )
            }
            if (release != null) UpdateButton(model) { model.startUpdate(ctx) }
            else KavButton(if (checking) T("Checking…", "בודקים…") else T("Check for updates", "בדקו עדכונים"), {
                if (checking) return@KavButton
                scope.launch {
                    checking = true
                    model.checkForUpdate(ctx)
                    checking = false
                }
            }, style = ButtonStyle.Secondary)
        }
        (release ?: installedNotes)?.let { ReleaseNotes(it, maxHeight = 240.dp) }
        UpdateProgress(model)
    }
}
