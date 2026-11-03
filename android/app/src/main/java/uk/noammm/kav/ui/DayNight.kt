package uk.noammm.kav.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import uk.noammm.kav.Prefs

// Settings' looks: OLED, Light and Dark, one choice.
@Composable
internal fun LookRow(inset: Dp) {
    val ctx = LocalContext.current
    val looks = listOf(Look.OLED, Look.LIGHT, Look.DARK)
    KavSegmented(
        listOf("OLED", T("Light", "בהיר"), T("Dark", "כהה")), looks.indexOf(K.look).coerceAtLeast(0),
        { i -> K.applyTheme(looks[i]); Prefs.setLook(ctx, looks[i]) },
        Modifier.padding(horizontal = inset),
    )
}
