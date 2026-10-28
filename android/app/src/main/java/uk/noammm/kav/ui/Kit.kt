package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Kav's controls, one of each: buttons, icon buttons, chips, a segmented control, grouped lists and their rows, section
// and screen headers, cards, a sheet's header, one switch and a line's plate. Their sizes, shapes, type and colours all
// come from K, so a screen built of them looks like every other.

enum class ButtonStyle { Primary, Secondary, Plain, Destructive }

// Primary: the one main action of a screen or sheet. Secondary: the others. Plain: an action in a line of text.
// Destructive: one that ends or removes something. busy: a spinner beside the label, the button's width kept.
@Composable
fun KavButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    icon: ImageVector? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    wide: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) .97f else 1f, tween(100), label = "press")
    val fill = when (style) {
        ButtonStyle.Primary -> if (enabled) K.accent else K.fill
        ButtonStyle.Secondary, ButtonStyle.Destructive -> K.fill
        ButtonStyle.Plain -> Color.Transparent
    }
    val ink = when {
        !enabled -> K.textTertiary
        style == ButtonStyle.Primary -> K.onAccent
        style == ButtonStyle.Secondary -> K.text
        style == ButtonStyle.Plain -> K.accentInk
        else -> K.critical
    }
    Row(
        modifier
            .then(if (wide) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = if (style == ButtonStyle.Primary) 52.dp else 48.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(K.capsule)
            .background(fill)
            .clickable(source, LocalIndication.current, enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (style == ButtonStyle.Plain) K.gap4 else K.gap5),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            Spinner(16.dp, track = ink.copy(alpha = .25f), arc = ink)
            Spacer(Modifier.width(K.gap2))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(K.gap2))
        }
        // A label that changes (Show, Hide) fades from one to the other while the button eases to its new width.
        ChangingText(label) { Text(it, style = K.type.bodyStrong, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// Words that change in place (a label, a status): the old fade out as the new fade in, and what holds them eases to its
// new size rather than jumping.
@Composable
fun ChangingText(text: String, modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    AnimatedContent(
        text, modifier,
        transitionSpec = {
            (fadeIn(tween(220)) togetherWith fadeOut(tween(150)))
                .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
        },
        label = "changingText",
    ) { content(it) }
}

// A value that steps (a time moved Later or Earlier, a count): the new one rolls in from the way it moved, up when it
// grew and down when it shrank, as a clock's digits turn. order: how two values compare; by default as text.
@Composable
fun RollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = K.type.body,
    order: Comparator<String> = naturalOrder(),
) {
    AnimatedContent(
        text, modifier,
        transitionSpec = {
            val up = order.compare(targetState, initialState) >= 0
            ((slideInVertically(spring(dampingRatio = 1f, stiffness = 500f)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(220))) togetherWith
                (slideOutVertically(spring(dampingRatio = 1f, stiffness = 500f)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(150))))
                .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
        },
        label = "rollingText",
    ) { Text(it, style = style, maxLines = 1) }
}

// A round icon button: Back, Share, Settings, Swap, Close. floating: glass, for one over a map or a list.
@Composable
fun KavIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    tint: Color = K.textSecondary,
    size: Dp = 48.dp,
) {
    Box(
        modifier
            .size(size)
            .then(if (floating) Modifier.glassSurface(size / 2) else Modifier.clip(CircleShape).background(K.fill))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (size < 44.dp) 20.dp else 24.dp))
    }
}

// A choice among a few, filled with the accent while chosen; a menu with a trailing ExpandMore. check: a tick on a
// chosen one, for a filter that takes several at once. Choosing never changes a chip's size: the tick's place is
// always kept, and only the colours and the tick fade.
@Composable
fun KavChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    check: Boolean = false,
) {
    val fill by animateColorAsState(if (selected) K.accent else K.fill, tween(150), label = "chipFill")
    val ink by animateColorAsState(if (selected) K.onAccent else K.textSecondary, tween(150), label = "chipInk")
    val ticked by animateFloatAsState(if (selected) 1f else 0f, tween(150), label = "chipTick")
    // With a tick, the label stays centred in room that fits the tick at its start, so choosing moves nothing.
    if (check) {
        Box(
            modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = 36.dp)
                .clip(K.capsule)
                .background(fill)
                .semantics { this.selected = selected }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ChangingText(label, Modifier.padding(horizontal = 29.dp)) { Text(it, style = K.type.subhead, color = ink, maxLines = 1) }
            Icon(
                Icons.Rounded.Check, null, tint = ink,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(18.dp).graphicsLayer { alpha = ticked },
            )
        }
        return
    }
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 36.dp)
            .clip(K.capsule)
            .background(fill)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        ChangingText(label) { Text(it, style = K.type.subhead, color = ink, maxLines = 1) }
        if (trailing != null) Icon(trailing, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
    }
}

// One choice of a few that changes a view: equal segments in one capsule, the chosen one on an accent thumb that slides
// to it, as a chosen chip is filled. Text only; five segments at most.
@Composable
fun KavSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp).clip(K.capsule).background(K.fill).padding(3.dp)) {
        val each = maxWidth / options.size.coerceAtLeast(1)
        val at by animateDpAsState(each * selected, spring(dampingRatio = 1f, stiffness = 500f), label = "thumb")
        Box(Modifier.offset(x = at).width(each).fillMaxHeight().clip(K.capsule).background(K.accent))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, option ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(K.capsule)
                        .semantics { this.selected = i == selected }
                        .clickable(role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    val ink by animateColorAsState(if (i == selected) K.onAccent else K.textSecondary, tween(150), label = "segmentInk")
                    Text(
                        option, style = K.type.subhead, maxLines = 1, color = ink,
                        fontWeight = if (i == selected) FontWeight.Medium else null,
                    )
                }
            }
        }
    }
}

class ListGroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows += content }
}

// Rows that belong together, on one card: the card's own shade, a hairline round it and between its rows, inset to
// where their text starts. The header and the footer sit outside it.
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    inset: Dp = K.gap4,
    content: ListGroupScope.() -> Unit,
) {
    val rows = ListGroupScope().apply(content).rows
    Column(modifier.fillMaxWidth()) {
        header?.let { SectionHeader(it) }
        Column(Modifier.fillMaxWidth().panel(K.rCard)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Separator(inset)
                row()
            }
        }
        footer?.let {
            Text(
                it, style = K.type.subhead, color = K.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
            )
        }
    }
}

// A hairline across a group, from inset at its start.
@Composable
fun Separator(inset: Dp = K.gap4) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(K.separator))
}

// One row of a grouped list a LazyColumn lays out: the rows share one card, rounded at the first and the last, with a
// hairline between them from inset at the start.
fun Modifier.groupItem(first: Boolean, last: Boolean, inset: Dp = K.gap4): Modifier {
    val r = K.rCard
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    val line = K.separator
    return clip(shape).background(K.surface).drawWithContent {
        drawContent()
        val w = .5.dp.toPx()
        val radius = r.toPx()
        // The card's outline, with the ends that belong to other rows pushed out of sight.
        val top = if (first) w / 2 else -radius * 2
        val bottom = if (last) size.height - w / 2 else size.height + radius * 2
        drawRoundRect(
            line, Offset(w / 2, top), Size(size.width - w, bottom - top), CornerRadius(radius - w / 2), style = Stroke(w),
        )
        if (!first) {
            val start = inset.toPx()
            if (layoutDirection == LayoutDirection.Ltr) drawLine(line, Offset(start, w / 2), Offset(size.width, w / 2), w)
            else drawLine(line, Offset(0f, w / 2), Offset(size.width - start, w / 2), w)
        }
    }
}

// A row of a list: its title and a line under it, something at its start (an icon, a tile), and at its end a value or
// a control; a chevron where it opens something.
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    titleColor: Color = K.text,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle != null) 64.dp else 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = K.type.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        value?.let { Text(it, style = K.type.subhead.tabular, maxLines = 1, softWrap = false) }
        trailing?.invoke()
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// A section's header: small, lined up with its rows' text, with an action at its end where it has one.
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap2, top = K.gap6, bottom = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = K.type.caption, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action, style = K.type.caption, color = K.accentInk,
                modifier = Modifier.minimumInteractiveComponentSize().clip(K.capsule)
                    .clickable(role = Role.Button, onClick = onAction).padding(horizontal = K.gap2),
            )
        }
    }
}

// A screen's header. large: a tab's own name, big, at the start; else a pushed screen's title beside Back.
@Composable
fun ScreenHeader(
    title: String,
    back: (() -> Unit)? = null,
    large: Boolean = back == null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap3).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        if (back != null) BackButton(back)
        Text(
            title, style = if (large) K.type.largeTitle else K.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

// A card: its own shade, a hairline, 16 dp inside.
@Composable
fun KavCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().panel(K.rCard)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(K.gap4),
        content = content,
    )
}

// A sheet's title row: its title, a line under it where there is one, and Close.
@Composable
fun SheetHeader(title: String, onClose: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = K.type.title)
            subtitle?.let { Text(it, style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        KavIconButton(Icons.Rounded.Close, T("Close", "סגירה"), onClose, size = 36.dp)
    }
}

// The one switch: the accent when on, a quiet track when off, a white thumb that slides across.
@Composable
fun KavSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (on) K.accent else K.fillStrong, label = "track")
    val at by animateDpAsState(if (on) 22.dp else 2.dp, spring(dampingRatio = 1f, stiffness = 500f), label = "thumb")
    Box(modifier.size(52.dp, 32.dp).clip(K.capsule).background(track)) {
        Box(Modifier.offset(x = at, y = 2.dp).size(28.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

// A line's number on its plate: the mode's glyph and the number, on the mode's or operator's colour (linePlate) or
// Kav's quiet fill. 44 dp wide at least, so plates in a column line up; dense: the smaller one, in a line of text.
@Composable
fun LinePlateBadge(
    number: String?,
    mode: Mode,
    plate: ModePlate?,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    glyph: (@Composable (ink: Color, size: Dp) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(K.rBadge)
    val ink = plate?.ink ?: K.text
    val mark = if (dense) 12.dp else 14.dp

    if (K.lineBadgeAppearance== LineBadgeAppearance.KAV || mode!=Mode.BUS) {
        Row(
            modifier
                .widthIn(min = 44.dp)
                .height(if (dense) 24.dp else 28.dp)
                .clip(shape)
                .background(plate?.fill ?: K.fill)
                .then(if (plate != null) Modifier.border(1.dp, plate.edge, shape) else Modifier)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            if (glyph != null) glyph(plate?.ink ?: K.textSecondary, mark) else ModeGlyph(
                mode,
                plate?.ink ?: K.textSecondary,
                mark
            )
            if (!number.isNullOrBlank()) {
                Text(
                    number,
                    style = (if (dense) K.type.subhead else K.type.bodyStrong).tabular,
                    color = ink,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
    else {
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(plate?.fill ?: K.fill)
                .border(1.dp, K.borderStrong, RoundedCornerShape(6.dp))

            ,
        ) {
            Row(
                Modifier.padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(K.badgePlate),
                verticalAlignment = Alignment.Top,
            ) {
                Row(Modifier.padding(start = 5.dp, end = if (number == null) 5.dp else 6.dp, top = 2.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                   ModeGlyph(
                        mode,
                        K.textSecondary,
                        mark
                    )
                    if (number != null) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            number, fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// The width a LinePlateBadge takes for a number, for lining plates up in a column.
internal fun linePlateWidth(numberWidth: Dp, dense: Boolean = false): Dp =
    maxOf(44.dp, 12.dp + (if (dense) 12.dp else 14.dp) + if (numberWidth > 0.dp) 4.dp + numberWidth else 0.dp)
