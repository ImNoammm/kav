package uk.noammm.kav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Neutral grouped surfaces with one quiet accent for actions and live guidance. */
object K {
    val bg = Color(0xFF101012)
    val surface1 = Color(0xFF202023)
    val surface2 = Color(0xFF2B2B30)
    val surface3 = Color(0xFF343439)
    val surface4 = Color(0xFF44444A)
    val border = Color(0xFF343439)
    val borderStrong = Color(0xFF74747D)
    val dim = Color(0xFF9C9CA5)
    val muted = Color(0xFFC7C7CE)
    val text = Color(0xFFF5F5F7)
    val plate = Color(0x0FFFFFFF)
    val plateStrong = Color(0x1FFFFFFF)
    val sunken = Color(0xFF19191C)
    val badgePlate = surface3
    val co2Pill = surface2

    val rCard = 22.dp
    val rControl = 16.dp
    val rPill = 999.dp
    val elevCard = 2.dp
    val elevRaised = 8.dp

    /**
     * The one colour in the app. It is state rather than a constant so the picker can
     * change it while you watch; Prefs keeps whatever was chosen. Everything that used
     * to name a separate route or live tint reads the same value.
     */
    var accent by mutableStateOf(DefaultAccent)
    val route get() = accent
    val live get() = accent
    val routeIdle = Color(0xFF7E7E87)
    val problem = Color(0xFFE7C17A)
    val critical = Color(0xFFEE929A)
    val scheduled = muted

    val gap1 = 4.dp; val gap2 = 8.dp; val gap3 = 12.dp
    val gap4 = 16.dp; val gap5 = 20.dp; val gap6 = 24.dp; val gap8 = 32.dp
}

/** Native sans-serif headings, with weight carrying hierarchy. */
val Display = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
    fontSize = 21.sp, color = K.text,
)
val DisplayItalic = Display.copy(fontWeight = FontWeight.Medium)
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = K.muted)

// The container slots matter as much as the base ones: a Material component that
// paints a "selected" or "filled" state reaches for primaryContainer, and anything
// left unset falls through to darkColorScheme's own purple, which is how the time
// field in the departure sheet came out lilac in a monochrome app. Every slot the
// app can reach is a grey from K.
private val scheme = darkColorScheme(
    primary = K.text, onPrimary = K.bg,
    primaryContainer = K.surface3, onPrimaryContainer = K.text,
    inversePrimary = K.surface4,
    secondary = K.muted, onSecondary = K.bg,
    secondaryContainer = K.surface2, onSecondaryContainer = K.text,
    tertiary = K.muted, onTertiary = K.bg,
    tertiaryContainer = K.surface2, onTertiaryContainer = K.text,
    background = K.bg, onBackground = K.text,
    surface = K.bg, onSurface = K.text,
    surfaceVariant = K.surface3, onSurfaceVariant = K.muted,
    surfaceTint = K.surface3,
    inverseSurface = K.muted, inverseOnSurface = K.bg,
    surfaceContainerLowest = K.bg, surfaceContainerLow = K.sunken,
    surfaceContainer = K.surface1, surfaceContainerHigh = K.surface2,
    surfaceContainerHighest = K.surface3,
    outline = K.border, outlineVariant = K.border,
    error = K.critical, onError = K.bg,
    errorContainer = K.surface3, onErrorContainer = K.critical,
)

@Composable
fun KavTheme(content: @Composable () -> Unit) {
    // There is no light theme. The map palette this is built around only exists
    // in the dark one, and a second palette would be a second thing to keep true.
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 14.sp, color = K.text),
            bodyMedium = TextStyle(fontSize = 13.sp, color = K.text),
            bodySmall = TextStyle(fontSize = 11.sp, color = K.dim),
        ),
        content = content,
    )
}
