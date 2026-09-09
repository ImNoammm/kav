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

/**
 * The first launch: pick the colour, then say what a plan may contain. Both are the
 * same controls Settings has, so nothing learned here has to be learned again.
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
            } else {
                Text("What should a plan show?", style = Display, fontSize = 26.sp)
                Text(
                    "Everything is on. Switch off what you never take and the planner " +
                        "leaves it out, this is the same list Settings keeps.",
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
                OnboardingButton("Done", onDone)
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
