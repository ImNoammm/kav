package uk.noammm.kav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

enum class Look { DARK, OLED, LIGHT }

object K {
    var look by mutableStateOf(Look.DARK)
    val light get() = look == Look.LIGHT
    var liquid by mutableStateOf(false)

    var bg by mutableStateOf(Color(0xFF101012))
    var surface1 by mutableStateOf(Color(0xFF202023))
    var surface2 by mutableStateOf(Color(0xFF2B2B30))
    var surface3 by mutableStateOf(Color(0xFF343439))
    var surface4 by mutableStateOf(Color(0xFF44444A))
    var border by mutableStateOf(Color(0xFF343439))
    var borderStrong by mutableStateOf(Color(0xFF74747D))
    var dim by mutableStateOf(Color(0xFF9C9CA5))
    var muted by mutableStateOf(Color(0xFFC7C7CE))
    var text by mutableStateOf(Color(0xFFF5F5F7))
    var plate by mutableStateOf(Color(0x0FFFFFFF))
    var plateStrong by mutableStateOf(Color(0x1FFFFFFF))
    var sunken by mutableStateOf(Color(0xFF19191C))
    val badgePlate get() = surface3
    val co2Pill get() = surface2

    val rCard = 22.dp
    val rControl = 16.dp
    val rPill = 16.dp

    var accent by mutableStateOf(DefaultAccent)
    // Accents are always pale, so text on them stays dark in every look.
    val onAccent get() = if (light) text else bg
    val route get() = accent
    val live get() = accent
    var routeIdle by mutableStateOf(Color(0xFF7E7E87))
    var problem by mutableStateOf(Color(0xFFE7C17A))
    var critical by mutableStateOf(Color(0xFFEE929A))
    val scheduled get() = muted

    val gap1 = 4.dp; val gap2 = 8.dp; val gap3 = 12.dp
    val gap4 = 16.dp; val gap5 = 20.dp; val gap6 = 24.dp; val gap8 = 32.dp

    // Every colour has to be set for every look, or a switch leaves strays from the last one.
    fun applyTheme(chosen: Look) {
        look = chosen
        when (chosen) {
            Look.DARK -> {
                bg = Color(0xFF101012); surface1 = Color(0xFF202023)
                surface2 = Color(0xFF2B2B30); surface3 = Color(0xFF343439)
                surface4 = Color(0xFF44444A); border = Color.White
                borderStrong = Color(0xFF74747D); dim = Color(0xFF9C9CA5)
                muted = Color(0xFFC7C7CE); text = Color(0xFFF5F5F7)
                plate = Color(0x0FFFFFFF); plateStrong = Color(0x1FFFFFFF)
                sunken = Color(0xFF19191C); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A)
            }
            Look.OLED -> {
                bg = Color(0xFF000000); surface1 = Color(0xFF000000)
                surface2 = Color(0xFF000000); surface3 = Color(0xFF000000)
                surface4 = Color(0xFF38383E); border = Color.White
                borderStrong = Color(0xFF6C6C75); dim = Color(0xFF9C9CA5)
                muted = Color(0xFFC7C7CE); text = Color(0xFFF5F5F7)
                plate = Color(0x14FFFFFF); plateStrong = Color(0x24FFFFFF)
                sunken = Color(0xFF000000); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A)
            }
            Look.LIGHT -> {
                bg = Color(0xFFF6F6F3); surface1 = Color(0xFFEBEBE7)
                surface2 = Color(0xFFE0E0DC); surface3 = Color(0xFFD5D5D1)
                surface4 = Color(0xFFC3C3BF); border = Color(0xFFDADAD6)
                borderStrong = Color(0xFF97979F); dim = Color(0xFF6F6F78)
                muted = Color(0xFF494951); text = Color(0xFF16161A)
                plate = Color(0x0D000000); plateStrong = Color(0x1A000000)
                sunken = Color(0xFFEFEFEB); routeIdle = Color(0xFFA6A6AE)
                problem = Color(0xFF9A6A00); critical = Color(0xFFB3424E)
            }
        }
    }
}

object Shown {
    var co2 by mutableStateOf(false)
    var twelveHour by mutableStateOf(false)
}

val Display get() = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
    fontSize = 21.sp, color = K.text,
)
val DisplayItalic get() = Display.copy(fontWeight = FontWeight.Medium)
val Mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = K.muted)

private fun scheme() = (if (K.light) lightColorScheme() else darkColorScheme()).copy(
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
    MaterialTheme(
        colorScheme = scheme(),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 14.sp, color = K.text),
            bodyMedium = TextStyle(fontSize = 13.sp, color = K.text),
            bodySmall = TextStyle(fontSize = 11.sp, color = K.dim),
        ),
        content = content,
    )
}
