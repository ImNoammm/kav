package uk.noammm.kav.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.ImportPrompt
import uk.noammm.kav.KavModel
import uk.noammm.kav.LookPrompt
import uk.noammm.kav.PendingBackup
import uk.noammm.kav.Prefs
import uk.noammm.kav.SupportPrompt
import uk.noammm.kav.data.AutoUpdate
import uk.noammm.kav.data.StartNotice
import uk.noammm.kav.data.WhatsNew

// Every popup Kav puts up by itself as it opens, in the order they go: a look to pick after a big update, the message
// this version brings (StartNotice), the map to download, a backup the rider opened, the "Enjoying Kav?" note, a new
// version (Tell me about new versions), one Kav downloaded itself to install (Download updates automatically), and
// "Kav has been updated" with what's new, after any update. Setup comes before all of them, as Root shows nothing else
// until it's done.
enum class StartPopup { LOOK, NOTICE, MAP, IMPORT, SUPPORT, UPDATE, READY, UPDATED }

// Which one shows: one at a time, kept until it closes, and one a time Kav opens, the rest waiting for the next opening.
// One the rider asked for (a backup or a link they opened) still waits for the one showing to close, but not for the
// next opening.
internal class PopupQueue {
    private var showing: StartPopup? = null
    private var used = -1

    fun pick(wanted: Set<StartPopup>, asked: Set<StartPopup>, opening: Int): StartPopup? {
        showing?.let { if (it in wanted) return it }
        showing = StartPopup.entries.firstOrNull { it in wanted && (it in asked || used != opening) }
        if (showing != null && showing !in asked) used = opening
        return showing
    }
}

// Whether "Enjoying Kav?" sits this opening out. It asks for a coffee as the message does, so it never comes right after
// it: not in the opening the message closed in, nor in the next, even when Kav was closed in between (StartNotice keeps
// that, and the next opening uses it up). Decided once an opening, as the queue picks, so nothing brings it back during
// one.
internal class SupportWait {
    private var opening = -1
    private var held = false

    fun held(opening: Int, waits: () -> Boolean, waited: () -> Unit): Boolean {
        if (opening != this.opening) {
            this.opening = opening
            held = waits()
            if (held) waited()
        }
        return held
    }

    // The message closed in this opening, which may have begun with it already showing.
    fun hold(opening: Int) {
        this.opening = opening
        held = true
    }
}

private val queue = PopupQueue()
private val supportWait = SupportWait()

// THE way to show a popup when Kav opens. A new one gets a place in StartPopup and a line below, never a Dialog of its
// own in Root or a screen, so that two can never be on top of each other.
@Composable
fun StartPopups(model: KavModel) {
    val ctx = LocalContext.current
    var pickLook by remember { mutableStateOf(Prefs.pickLook(ctx)) }
    var pickSupport by remember { mutableStateOf(Prefs.pickSupport(ctx)) }
    var notice by remember { mutableStateOf(StartNotice.notice(ctx)) }
    // From the phone alone, at each opening: an update downloaded, and one Kav just had, by itself or by hand. Nothing is
    // asked of the network here (Checks downloads in the background).
    var updated by remember { mutableStateOf<AutoUpdate.Notice?>(null) }
    var ready by remember { mutableStateOf<AutoUpdate.Notice?>(null) }
    // Later: not again until Kav next opens, when it's wanted again at once.
    var readyLater by remember { mutableIntStateOf(-1) }
    val opening = Openings.count
    val supportWaits = supportWait.held(opening, { StartNotice.supportWaits(ctx) }) { StartNotice.supportWaited(ctx) }
    LaunchedEffect(opening) {
        val app = ctx.applicationContext
        if (updated == null) updated = withContext(Dispatchers.IO) { AutoUpdate.notice(app) ?: WhatsNew.notice(app) }
        if (ready == null && Prefs.autoUpdate(app)) ready = withContext(Dispatchers.IO) { AutoUpdate.ready(app) }
    }
    val wanted = buildSet {
        if (pickLook) add(StartPopup.LOOK)
        if (notice != null) add(StartPopup.NOTICE)
        if (mapPromptWanted) add(StartPopup.MAP)
        if (PendingBackup.uri != null) add(StartPopup.IMPORT)
        if (pickSupport && !supportWaits) add(StartPopup.SUPPORT)
        // With automatic updates on, Kav asks about the version it downloaded instead.
        if (model.update != null && !model.updateDismissed && Shown.updatePrompts && !Prefs.autoUpdate(ctx)) add(StartPopup.UPDATE)
        if (ready != null && readyLater != opening) add(StartPopup.READY)
        if (updated != null) add(StartPopup.UPDATED)
    }
    when (queue.pick(wanted, setOf(StartPopup.IMPORT), opening)) {
        StartPopup.LOOK -> LookPrompt { Prefs.lookPicked(ctx); pickLook = false }
        StartPopup.NOTICE -> notice?.let { n ->
            NoticePrompt(n) { coffee ->
                StartNotice.close(n, coffee, { openLink(ctx, it) }) { StartNotice.closed(ctx, it) }
                supportWait.hold(Openings.count)
                notice = null
            }
        }
        StartPopup.MAP -> MapPrompt()
        StartPopup.IMPORT -> ImportPrompt(model)
        StartPopup.SUPPORT -> SupportPrompt { Prefs.supportShown(ctx); pickSupport = false }
        StartPopup.UPDATE -> UpdatePrompt(model)
        StartPopup.READY -> ready?.let { ReadyPrompt(it) { readyLater = opening } }
        StartPopup.UPDATED -> updated?.let { UpdatedNotice(it) { AutoUpdate.noticeShown(ctx); WhatsNew.shown(ctx); updated = null } }
        null -> {}
    }
}
