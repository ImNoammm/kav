package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.MapFile

/**
 * The first launch: pick the colour, say what a plan may contain, then fetch the
 * map. The first two are the same controls Settings has, so nothing learned here
 * has to be learned again; the third is the same offer the map itself makes.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var filters by remember { mutableStateOf(Prefs.filters(ctx)) }
    androidx.activity.compose.BackHandler(enabled = page > 0) { page-- }

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
                Text("Pick a colour", style = Display, fontSize = 26.sp)
                Text(
                    "Kav is grey with one colour on top. Drag the dot, or take a preset; " +
                        "the preview follows as you go, and Settings has this again later.",
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap6))
                AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton("Next") { page = 1 }
            } else if (p == 1) {
                Text("What should a plan show?", style = Display, fontSize = 26.sp)
                Text(
                    "Everything is on. Switch off what you never take and the planner " +
                        "leaves it out. This is the same list Settings keeps.",
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                Box(Modifier.padding(horizontal = 0.dp)) {
                    FilterRows(filters) { f, on ->
                        filters = if (on) filters + f else filters - f
                        Prefs.setFilter(ctx, f, on)
                    }
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton("Next") { page = 2 }
            } else {
                val state = MapFile.state
                Text("Download the map", style = Display, fontSize = 26.sp)
                Text(
                    "Kav keeps its map on your phone instead of loading tiles from a server as " +
                        "you go, so nothing tracks where you look. It's about ${MapFile.BYTES shr 20} MB for all " +
                        "of Israel, once. After that the map works with no signal. Get it now or " +
                        "later from the map itself.",
                    fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                when (state) {
                    is MapFile.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)).background(K.surface4)) {
                            Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
                        }
                        Text("Downloading… ${(state.progress * 100).toInt()}%", fontSize = 12.sp, color = K.dim)
                    }
                    is MapFile.State.Failed ->
                        Text("Couldn't download it. ${state.why}", fontSize = 12.sp, color = K.critical, lineHeight = 17.sp)
                    is MapFile.State.Ready ->
                        Text("Got it. The map stays on your phone from now on.", fontSize = 13.sp, color = K.muted)
                    else -> {}
                }
                Spacer(Modifier.height(K.gap8))
                when (state) {
                    is MapFile.State.Ready -> OnboardingButton("Done", onDone)
                    is MapFile.State.Downloading -> OnboardingButton("Continue while it downloads", onDone)
                    is MapFile.State.Failed -> OnboardingButton("Try again") { MapFile.startDownload(ctx) }
                    else -> OnboardingButton("Download") { MapFile.startDownload(ctx) }
                }
                if (state !is MapFile.State.Ready && state !is MapFile.State.Downloading) {
                    Spacer(Modifier.height(K.gap3))
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                            .clickable(role = Role.Button, onClick = onDone),
                        contentAlignment = Alignment.Center,
                    ) { Text("Later", fontSize = 15.sp, color = K.muted) }
                }
            }
            Spacer(Modifier.height(K.gap6))
        }
    }
}

@Composable
private fun OnboardingButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(K.rPill)).background(K.accent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = K.bg, fontWeight = FontWeight.Medium)
    }
}
