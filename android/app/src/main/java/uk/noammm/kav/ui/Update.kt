package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import uk.noammm.kav.BuildConfig
import uk.noammm.kav.KavModel
import uk.noammm.kav.R
import uk.noammm.kav.data.AutoUpdate
import uk.noammm.kav.data.StartNotice
import uk.noammm.kav.data.Updates
import androidx.compose.runtime.LaunchedEffect

@Composable
fun UpdatePrompt(model: KavModel) {
    val release = model.update ?: return
    if (model.updateDismissed || !Shown.updatePrompts) return
    val ctx = LocalContext.current
    ReleasePrompt("Kav", "${Updates.installedVersion(ctx)} → ${release.version}", release, { model.updateDismissed = true }, { UpdateProgress(model) }) {
        UpdateButton(model, Modifier.weight(1f)) { model.startUpdate(ctx) }
    }
}

// Install now while it's running, and whether the last one didn't go through (InstallStatus).
object UpdateRun {
    var installing by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

// An update Kav downloaded and checked in the background (AutoUpdate), asked about as Kav opens: the update prompt with
// nothing left to download. Install now hands it to Android's installer, which closes Kav; Later asks again next time.
@Composable
internal fun ReadyPrompt(notice: AutoUpdate.Notice, onLater: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = UpdateRun.installing
    ReleasePrompt(
        T("Kav ${notice.to} is ready 🎉", "Kav ${notice.to} מוכנה 🎉"), "${notice.from} → ${notice.to}", notice.release(),
        { if (!busy) { UpdateRun.failed = false; onLater() } },
        {
            if (UpdateRun.failed) Text(T("It didn't install.", "ההתקנה לא הושלמה."), fontSize = 12.sp, color = K.critical)
            else if (busy) Text(T("Installing: Kav closes when it's done.", "מתקינים: Kav תיסגר כשזה יסתיים."), fontSize = 12.sp, color = K.dim)
        },
    ) {
        KavButton(T("Install now", "להתקין עכשיו"), {
            if (busy) return@KavButton
            if (!Updates.canInstall(ctx)) { Updates.askInstallPermission(ctx); return@KavButton }
            UpdateRun.installing = true
            UpdateRun.failed = false
            val app = ctx.applicationContext
            scope.launch {
                val handed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { AutoUpdate.installNow(app) }.getOrDefault(false) }
                if (!handed) { UpdateRun.installing = false; UpdateRun.failed = true }
            }
        }, Modifier.weight(1f), busy = busy)
    }
}

// A new version's popup: Kav's icon, the versions, the release's notes from GitHub, and Later (when there's one to
// put off) beside its action.
@Composable
private fun ReleasePrompt(
    title: String, versions: String, release: Updates.Release, onLater: () -> Unit, status: @Composable () -> Unit = {},
    later: Boolean = true, action: @Composable RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                AppIcon(56.dp)
                Column {
                    Text(title, style = K.type.title)
                    Text(T.ltr(versions), style = K.type.subhead.tabular)
                }
            }
            ReleaseNotes(release, maxHeight = 260.dp)
            status()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                if (later) KavButton(T("Later", "אחר כך"), onLater, Modifier.weight(1f).heightIn(min = 52.dp), style = ButtonStyle.Secondary)
                action()
            }
        }
    }
}

@Composable
private fun AppIcon(size: androidx.compose.ui.unit.Dp) {
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
private fun ReleaseNotes(release: Updates.Release, maxHeight: androidx.compose.ui.unit.Dp) {
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
    // The notes of the version in hand, from the newest release the background check kept, when that's this one: opening
    // this page asks GitHub nothing.
    var installedNotes by remember { mutableStateOf<Updates.Release?>(null) }
    LaunchedEffect(release) {
        if (release != null) return@LaunchedEffect
        installedNotes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updates.stored(ctx) }
            ?.takeIf { it.version.removePrefix("v") == Updates.installedVersion(ctx).removePrefix("v") }
    }
    Column(Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(K.rCard).padding(K.gap4)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            AppIcon(40.dp)
            // A new status fades in over the old, and the card eases to any new height, as the button beside it changes width.
            Column(Modifier.weight(1f).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
                Text(T.ltr("Kav ${Updates.installedVersion(ctx)}"), style = K.type.headline)
                val available = release?.let { T("${it.version} is available", "גרסה ${it.version} זמינה") }
                // Short enough for one line beside the button in both languages.
                ChangingText(
                    available ?: when {
                        checking -> T("Looking…", "בודקים…")
                        model.updateFailed -> T("Couldn't check", "הבדיקה נכשלה")
                        model.updateChecked -> T("Up to date", "גרסה עדכנית")
                        else -> T("Not checked yet", "עוד לא נבדק")
                    },
                ) { Text(it, style = K.type.subhead, color = if (it == available) K.accentInk else K.textSecondary) }
            }
            if (release != null) UpdateButton(model) { model.startUpdate(ctx) }
            else KavButton(if (checking) T("Checking…", "בודקים…") else T("Check for updates", "בדקו עדכונים"), {
                if (checking) return@KavButton
                scope.launch {
                    checking = true
                    model.checkForUpdate(ctx)
                    checking = false
                }
            }, style = ButtonStyle.Secondary, busy = checking)
        }
        // The notes, once fetched, and the download open under the row as InfoHeader's text does.
        val notes = release ?: installedNotes
        var lastNotes by remember { mutableStateOf(notes) }
        if (notes != null) lastNotes = notes
        AnimatedVisibility(notes != null, enter = opening, exit = closing) {
            AnimatedContent(
                lastNotes,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                },
                label = "releaseNotes",
            ) { shown -> shown?.let { Column(Modifier.padding(top = K.gap3)) { ReleaseNotes(it, maxHeight = 240.dp) } } }
        }
        AnimatedVisibility(model.updateProgress != null || model.updateError != null, enter = opening, exit = closing) {
            Column(Modifier.padding(top = K.gap3)) { UpdateProgress(model) }
        }
    }
}

// Said once, the first time Kav opens after an update, one it downloaded itself (AutoUpdate) or one installed by hand
// (WhatsNew): the version it came from, when known, and the one it's on, and what's new in it, open from the start as
// in ReadyPrompt.
@Composable
internal fun UpdatedNotice(notice: AutoUpdate.Notice, onClose: () -> Unit) =
    ReleasePrompt(
        T("Kav has been updated", "Kav עודכנה"), if (notice.from.isBlank()) notice.to else "${notice.from} → ${notice.to}",
        notice.release(), onClose, later = false,
    ) {
        KavButton(T("Close", "סגירה"), onClose, Modifier.weight(1f))
    }

// The message this version brings (StartNotice), once, in ReleasePrompt's look: its text, then why Kav needs help, and
// two ways to close it, Got it and Buy me a coffee, which opens the page as well. On a short screen the text gives way,
// so the ask and the buttons stay in view.
@Composable
internal fun NoticePrompt(notice: StartNotice.Message, onClose: (coffee: Boolean) -> Unit) {
    Dialog(onDismissRequest = { onClose(false) }) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                AppIcon(56.dp)
                Column {
                    Text(notice.title, style = K.type.title)
                    Text(T.ltr(BuildConfig.VERSION_NAME), style = K.type.subhead.tabular)
                }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Separator(0.dp)
                Spacer(Modifier.height(K.gap3))
                Text(notice.text, style = K.type.subhead)
            }
            Column {
                Separator(0.dp)
                Spacer(Modifier.height(K.gap3))
                Text(notice.helpTitle, style = K.type.headline)
                Text(notice.help, style = K.type.subhead, modifier = Modifier.padding(top = K.gap1))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(T("Got it", "הבנתי"), { onClose(false) }, Modifier.heightIn(min = 52.dp), style = ButtonStyle.Secondary)
                KavButton(T("Buy me a coffee", "קנו לי קפה"), { onClose(true) }, Modifier.weight(1f))
            }
        }
    }
}

private val opening = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60))
private val closing = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120))
