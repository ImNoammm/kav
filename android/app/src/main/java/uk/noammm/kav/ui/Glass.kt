package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.hazeChild

val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

private val glassStyle = HazeStyle(
    backgroundColor = K.bg,
    tints = listOf(HazeTint(K.surface1.copy(alpha = .62f))),
    blurRadius = 20.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(K.surface1.copy(alpha = .92f)),
)

/** One material for controls and floating panels, with a bounded backdrop blur. */
@Composable
fun Modifier.glassSurface(radius: Dp = 22.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    val source = LocalGlassBackdrop.current
    return clip(shape)
        .then(if (source != null) Modifier.hazeChild(source, style = glassStyle) { inputScale = HazeInputScale.Auto }
            else Modifier.background(K.surface1.copy(alpha = .94f)))
        .border(0.5.dp, Color.White.copy(alpha = .14f), shape)
}
