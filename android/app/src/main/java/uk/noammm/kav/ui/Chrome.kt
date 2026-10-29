package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Shortcuts for the kit's controls (Kit.kt), as the screens call them.

@Composable
fun BackButton(onClick: () -> Unit) =
    KavIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, T("Back", "חזרה"), onClick, floating = true, tint = K.text)

@Composable
fun ShareButton(onClick: () -> Unit) =
    KavIconButton(Icons.Rounded.Share, T("Share trip", "שיתוף נסיעה"), onClick, floating = true, tint = K.text)

// badge: a dot for something new behind it, an update.
@Composable
fun SettingsButton(badge: Boolean = false, onClick: () -> Unit) {
    Box {
        KavIconButton(Icons.Rounded.Settings, T("Settings", "הגדרות"), onClick, floating = true, tint = K.text)
        if (badge) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(12.dp)
                .clip(CircleShape).background(K.bg).padding(2.dp).clip(CircleShape).background(K.accent),
        )
    }
}

@Composable
fun Chip(text: String, lit: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    KavChip(text, lit, onClick, modifier)

@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = K.textSecondary) {
    Text(text, style = K.type.subhead, color = color, modifier = modifier)
}

// A screen's header with its title in two parts, read as one: a tab's big and at the start, a pushed screen's beside
// Back. onSettings: the settings button, at the end.
@Composable
fun ScreenHeader(
    plain: String, accent: String, onSettings: (() -> Unit)? = null, back: (() -> Unit)? = null,
    badge: Boolean = false,
) {
    ScreenHeader("$plain $accent".trim(), back = back) {
        if (onSettings != null) SettingsButton(badge, onSettings)
    }
}
