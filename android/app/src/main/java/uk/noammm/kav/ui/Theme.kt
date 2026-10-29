package uk.noammm.kav.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Look { DARK, OLED, LIGHT }

object K {
    var look by mutableStateOf(Look.DARK)
    val light get() = look == Look.LIGHT
    var liquid by mutableStateOf(false)

    // Colour roles, one meaning each. Cards stand off the page by their own shade and a hairline, never a white ring.
    var bg by mutableStateOf(Color(0xFF101012))
    // Cards and grouped lists.
    var surface by mutableStateOf(Color(0xFF1C1C1F))
    // Sheets, floating panels and menus.
    var surfaceRaised by mutableStateOf(Color(0xFF26262A))
    // A control's fill: chips, secondary buttons, fields; strong while pressed or emphasised.
    var fill by mutableStateOf(Color(0x14FFFFFF))
    var fillStrong by mutableStateOf(Color(0x24FFFFFF))
    // Hairlines between rows and around cards: see-through on the dark looks, solid on the light one (black at 9 % on
    // the page), where outlines are compared by their solid shade.
    var separator by mutableStateOf(Color(0x1FFFFFFF))
    var text by mutableStateOf(Color(0xFFF5F5F7))
    var textSecondary by mutableStateOf(Color(0xFFC7C7CE))
    // Placeholders, captions and anything disabled: never essential text.
    var textTertiary by mutableStateOf(Color(0xFF9C9CA5))
    var surface2 by mutableStateOf(Color(0xFF2B2B30))
    var surface3 by mutableStateOf(Color(0xFF343439))
    var surface4 by mutableStateOf(Color(0xFF44444A))
    var borderStrong by mutableStateOf(Color(0xFF74747D))
    var sunken by mutableStateOf(Color(0xFF19191C))

    // Other names for the roles above, as some screens call them.
    val surface1 get() = surface
    val border get() = separator
    val dim get() = textTertiary
    val muted get() = textSecondary
    val plate get() = fill
    val plateStrong get() = fillStrong
    val badgePlate get() = surface3
    val co2Pill get() = surface2

    val rCard = 22.dp
    // A sheet's top corners, and a full-screen dialog's.
    val rSheet = 28.dp
    // Photo and glyph tiles in rows, and small blocks inside a card.
    val rTile = 12.dp
    // Line plates.
    val rBadge = 8.dp
    // Every control: buttons, chips, segmented controls, fields, icon buttons.
    val capsule = RoundedCornerShape(percent = 50)

    // Interaction and selection only: filled primary buttons, chosen chips, switches, links.
    var accent by mutableStateOf(DefaultAccent)
    // Accents are always pale, so text on them stays dark in every look.
    val onAccent get() = if (light) text else bg
    // The accent as text and links. On white a pale accent is deepened until it reads at 4.5:1.
    val accentInk: Color get() = if (light) readable(accent, surface) else accent
    val route get() = accent
    val live get() = accent
    var routeIdle by mutableStateOf(Color(0xFF7E7E87))
    var problem by mutableStateOf(Color(0xFFE7C17A))
    var critical by mutableStateOf(Color(0xFFEE929A))
    // Moovit's green for a time or position measured live, and nothing else.
    var realtime by mutableStateOf(Color(0xFF04C876))
    val scheduled get() = textSecondary

    val gap1 = 4.dp; val gap2 = 8.dp; val gap3 = 12.dp
    val gap4 = 16.dp; val gap5 = 20.dp; val gap6 = 24.dp; val gap8 = 32.dp

    val type = KType
    var lineBadgeAppearance by mutableStateOf(LineBadgeAppearance.KAV)

    // Every colour has to be set for every look, or a switch leaves strays from the last one.
    fun applyTheme(chosen: Look) {
        look = chosen
        when (chosen) {
            Look.DARK -> {
                bg = Color(0xFF101012); surface = Color(0xFF1C1C1F); surfaceRaised = Color(0xFF26262A)
                fill = Color(0x14FFFFFF); fillStrong = Color(0x24FFFFFF); separator = Color(0x1FFFFFFF)
                text = Color(0xFFF5F5F7); textSecondary = Color(0xFFC7C7CE); textTertiary = Color(0xFF9C9CA5)
                surface2 = Color(0xFF2B2B30); surface3 = Color(0xFF343439); surface4 = Color(0xFF44444A)
                borderStrong = Color(0xFF74747D); sunken = Color(0xFF19191C); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A); realtime = Color(0xFF04C876)
            }
            Look.OLED -> {
                bg = Color(0xFF000000); surface = Color(0xFF0E0E10); surfaceRaised = Color(0xFF17171A)
                fill = Color(0x1AFFFFFF); fillStrong = Color(0x29FFFFFF); separator = Color(0x29FFFFFF)
                text = Color(0xFFF5F5F7); textSecondary = Color(0xFFC7C7CE); textTertiary = Color(0xFF9C9CA5)
                surface2 = Color(0xFF1C1C1F); surface3 = Color(0xFF26262A); surface4 = Color(0xFF38383E)
                borderStrong = Color(0xFF6C6C75); sunken = Color(0xFF000000); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A); realtime = Color(0xFF04C876)
            }
            Look.LIGHT -> {
                bg = Color(0xFFF2F2EF); surface = Color(0xFFFFFFFF); surfaceRaised = Color(0xFFFFFFFF)
                fill = Color(0x0D000000); fillStrong = Color(0x17000000); separator = Color(0xFFDCDCD9)
                text = Color(0xFF16161A); textSecondary = Color(0xFF494951); textTertiary = Color(0xFF6F6F78)
                surface2 = Color(0xFFE6E6E2); surface3 = Color(0xFFD9D9D5); surface4 = Color(0xFFC3C3BF)
                borderStrong = Color(0xFF97979F); sunken = Color(0xFFEAEAE6); routeIdle = Color(0xFFA6A6AE)
                problem = Color(0xFF9A6A00); critical = Color(0xFFB3424E); realtime = Color(0xFF00804C)
            }
        }
    }
}

// A colour made dark enough to read at 4.5:1 on a background, for text in a colour chosen to look pale.
fun readable(c: Color, on: Color): Color {
    var out = c
    var t = 0f
    while (contrast(out, on) < 4.5f && t < .9f) {
        t += .05f
        out = lerp(c, Color.Black, t)
    }
    return out
}

// The type roles, seven in all. Weight carries hierarchy before size; nothing in the interface is under 12 sp.
object KType {
    // Top-level titles, and a trip's duration.
    val largeTitle get() = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = K.text)
    // Pushed screens' and sheets' titles.
    val title get() = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = K.text)
    // A card's title, the key line of a card.
    val headline get() = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = K.text)
    // Row titles, paragraphs, field text.
    val body get() = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = K.text)
    // Button labels and values that stand out.
    val bodyStrong get() = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = K.text)
    // A row's second line, chips, notes, footers.
    val subhead get() = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary)
    // Section headers, tab labels, captions under icons.
    val caption get() = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .2.sp, color = K.textSecondary)
}

// Figures of one width, for times and counts that change in place without the line jumping.
val TextStyle.tabular get() = copy(fontFeatureSettings = "tnum")

object Shown {
    var co2 by mutableStateOf(false)
    var twelveHour by mutableStateOf(false)
    // Paying for rides: the home screen's card and a ride's. Off, Kav shows nothing of it.
    var payments by mutableStateOf(true)
}

// Times in tabular figures of the system font.
val Mono get() = TextStyle(fontSize = 12.sp, color = K.muted).tabular

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
    surfaceContainer = K.surface, surfaceContainerHigh = K.surfaceRaised,
    surfaceContainerHighest = K.surface3,
    outline = K.separator, outlineVariant = K.separator,
    error = K.critical, onError = K.bg,
    errorContainer = K.surface3, onErrorContainer = K.critical,
)

@Composable
fun KavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme(),
        typography = Typography(
            titleLarge = K.type.largeTitle,
            titleMedium = K.type.title,
            titleSmall = K.type.headline,
            labelLarge = K.type.bodyStrong,
            labelMedium = K.type.caption,
            // For text that sets no style of its own.
            bodyLarge = TextStyle(fontSize = 14.sp, color = K.text),
            bodyMedium = TextStyle(fontSize = 13.sp, color = K.text),
            bodySmall = TextStyle(fontSize = 12.sp, color = K.dim),
        ),
        content = content,
    )
}
