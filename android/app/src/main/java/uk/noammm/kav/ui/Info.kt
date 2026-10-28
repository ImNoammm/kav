package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember

// A section's header with its explanation kept behind a small (i) at its end: the text slides open under the header
// when asked for, instead of sitting in grey under every control. A block of text sits in a card, like the Live
// screen's; a single line stays plain.
@Composable
fun InfoHeader(text: String, info: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(bottom = K.gap2)) {
        Row(Modifier.fillMaxWidth().padding(start = K.gap4, top = K.gap4), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = K.type.caption, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = T("About $text", "על $text")) { open = !open },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Info, contentDescription = T("About $text", "על $text"),
                    tint = if (open) K.accentInk else K.textTertiary, modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) {
            AnimatedContent(
                info, transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) }, label = "info",
            ) { shown ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val measurer = rememberTextMeasurer()
                    val width = constraints.maxWidth - with(LocalDensity.current) { (K.gap4 * 2).roundToPx() }
                    // Measured before it's drawn, so it never jumps from plain to card.
                    val block = remember(shown, width) {
                        measurer.measure(shown, K.type.subhead, constraints = Constraints(maxWidth = width.coerceAtLeast(0))).lineCount > 1
                    }
                    Text(
                        shown, style = K.type.subhead,
                        modifier = if (block) {
                            Modifier.padding(bottom = K.gap2).fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.fill)
                                .padding(horizontal = K.gap4, vertical = K.gap3)
                        } else Modifier.padding(horizontal = K.gap4),
                    )
                }
            }
        }
    }
}
