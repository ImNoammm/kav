package uk.noammm.kav.ui

import androidx.compose.foundation.Image
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
import uk.noammm.kav.R
import uk.noammm.kav.data.Updates

/**
 * "A newer Kav is out." Shown at launch while a newer release exists; saying No
 * closes it for this launch only, and the dot on Settings keeps the offer open.
 */
@Composable
fun UpdatePrompt(model: KavModel) {
    val release = model.update ?: return
    if (model.updateDismissed) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { model.updateDismissed = true }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                AppIcon(56.dp)
                Column {
                    Text("Kav", fontSize = 20.sp, color = K.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        T.ltr("${Updates.installedVersion(ctx)} → ${release.version}"),
                        fontSize = 15.sp, color = K.accent, fontWeight = FontWeight.Medium,
                    )
                }
            }
            ReleaseNotes(release, maxHeight = 260.dp)
            UpdateProgress(model)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                Box(
                    Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(K.rPill)).background(K.plateStrong)
                        .clickable(role = Role.Button) { model.updateDismissed = true },
                    contentAlignment = Alignment.Center,
                ) { Text(T("No", "לא"), fontSize = 15.sp, color = K.text) }
                UpdateButton(model, Modifier.weight(1f)) { scope.launch { model.installUpdate(ctx) } }
            }
        }
    }
}

/** The launcher mark on its own black plate, as the home screen shows it. */
@Composable
private fun AppIcon(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Kav",
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
    }
}

@Composable
private fun ReleaseNotes(release: Updates.Release, maxHeight: androidx.compose.ui.unit.Dp) {
    val notes = release.notes.trim().ifBlank { release.name.ifBlank { T("No release notes.", "אין מה חדש.") } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.sunken)
            .heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(K.gap3),
    ) {
        Text(notes, fontSize = 13.sp, color = K.muted, lineHeight = 19.sp)
    }
}

@Composable
private fun UpdateProgress(model: KavModel) {
    model.updateProgress?.let { p ->
        Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)).background(K.surface4)) {
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
    Box(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(K.rPill))
            .background(if (busy) K.surface4 else K.accent)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) T("Updating…", "מעדכנים…") else T("Update", "עדכון"), fontSize = 15.sp,
            color = if (busy) K.muted else K.bg, fontWeight = FontWeight.Medium,
        )
    }
}

/** The same offer inside Settings, for later, and a way to look again. */
@Composable
fun UpdateSection(model: KavModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val release = model.update
    var checking by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = K.gap3).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.plate)
            .padding(K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            AppIcon(40.dp)
            Column(Modifier.weight(1f)) {
                Text(T.ltr("Kav ${Updates.installedVersion(ctx)}"), fontSize = 14.sp, color = K.text)
                Text(
                    when {
                        release != null -> T("${release.version} is available", "גרסה ${release.version} זמינה")
                        checking -> T("Looking…", "בודקים…")
                        model.updateChecked -> T("This is the newest release", "זו הגרסה העדכנית ביותר")
                        else -> T("Not checked yet", "עוד לא נבדק")
                    },
                    fontSize = 12.sp, color = if (release != null) K.accent else K.dim,
                )
            }
            if (release != null) UpdateButton(model) { scope.launch { model.installUpdate(ctx) } }
            else Chip(if (checking) T("Checking…", "בודקים…") else T("Check for updates", "בדקו עדכונים"), false) {
                if (checking) return@Chip
                scope.launch {
                    checking = true
                    model.checkForUpdate(ctx)
                    checking = false
                }
            }
        }
        if (release != null) ReleaseNotes(release, maxHeight = 200.dp)
        UpdateProgress(model)
    }
}
