package uk.noammm.kav.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import kotlin.math.abs

// A live time, as a Text that changes calmly: when the words change, only what changed rolls, down to a sooner time and
// up to a later one; what only moved over slides there, or fades across when it went to another line; a new colour eases
// in; the rest stays still. Its box eases to the new size on the same curve, so what's beside or under it moves with it.
@Composable
fun LiveText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
) {
    val morph = remember { Morph() }
    morph.ids = inlineContent.keys
    // Each new text starts its change at once, so its first frame still shows the old one.
    val change = remember(text) { Animatable(if (morph.now == null) 1f else 0f) }
    LaunchedEffect(change) {
        change.animateTo(1f, tween(MORPH_MS, easing = LinearEasing))
        morph.was = null
        morph.plan = null
    }
    Text(
        text,
        modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val s = morph.size(text, IntSize(p.width, p.height), change.value)
                layout(s.width, s.height) { p.placeRelative(0, 0) }
            }
            .drawWithContent { drawMorph(morph, change.value) },
        style = style, maxLines = maxLines, overflow = overflow, inlineContent = inlineContent,
        onTextLayout = morph::laidOut,
    )
}

@Composable
fun LiveText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = LiveText(
    // The colour is part of the text, so a new one eases in with the rest of a change.
    AnnotatedString(text, SpanStyle(color = color, fontWeight = fontWeight, textDecoration = textDecoration)),
    modifier, style, maxLines, overflow,
)

private const val MORPH_MS = 320
private const val INLINE = "androidx.compose.foundation.text.inlineContent"

private class Morph {
    var now: TextLayoutResult? = null
    var was: TextLayoutResult? = null
    var plan: Plan? = null
    var ids: Set<String> = emptySet()
    val paint = Paint()
    private var sizedFor: AnnotatedString? = null
    private var from = IntSize.Zero
    private var shown: IntSize? = null

    fun laidOut(l: TextLayoutResult) {
        val before = now
        if (before != null && before.layoutInput.text != l.layoutInput.text) was = before
        now = l
        plan = null
    }

    // The box from where it was when the text changed to the new text's size.
    fun size(text: AnnotatedString, size: IntSize, t: Float): IntSize {
        if (text != sizedFor) {
            from = shown ?: size
            sizedFor = text
        }
        val e = FastOutSlowInEasing.transform(t)
        return IntSize(lerp(from.width, size.width, e), lerp(from.height, size.height, e)).also { shown = it }
    }
}

// A word, number or sign of a laid-out text, or an inline mark (mark, its index): what it says, its colour, and a box
// for each line it's on.
private class Bit(val key: String, val color: Color, val boxes: List<Rect>, val mark: Int = -1)

private const val SPACE = 0
private const val WORD = 1
private const val SIGN = 2

private fun kindOf(c: Char) = when {
    c.isWhitespace() || Character.getType(c) == Character.FORMAT.toInt() -> SPACE
    c.isLetterOrDigit() || c == ':' || c == '.' || c == '\'' || c == '׳' || c == '״' -> WORD
    else -> SIGN
}

private fun bits(l: TextLayoutResult, ids: Set<String>): List<Bit> {
    val text = l.layoutInput.text
    val s = text.text
    val last = l.lineCount - 1
    // Cut short with an ellipsis, only what's shown counts.
    val end = if (last >= 0 && l.isLineEllipsized(last)) l.getLineEnd(last, visibleEnd = true) else s.length
    val colors = Array(s.length) { l.layoutInput.style.color }
    for (r in text.spanStyles) if (r.item.color.isSpecified) for (i in r.start until minOf(r.end, s.length)) colors[i] = r.item.color
    val marks = text.getStringAnnotations(INLINE, 0, s.length).filter { it.item in ids }
    val out = ArrayList<Bit>()
    var i = 0
    while (i < end) {
        val m = marks.indexOfFirst { it.start == i }
        if (m >= 0) {
            l.placeholderRects.getOrNull(m)?.let { out.add(Bit("￼" + marks[m].item, Color.Unspecified, listOf(it), m)) }
            i = maxOf(marks[m].end, i + 1)
            continue
        }
        val kind = kindOf(s[i])
        var j = i + 1
        if (kind == WORD) while (j < end && kindOf(s[j]) == WORD && colors[j] == colors[i] && marks.none { it.start == j }) j++
        if (kind != SPACE) out.add(Bit(s.substring(i, j), colors[i], boxes(l, i, j)))
        i = j
    }
    return out
}

private fun boxes(l: TextLayoutResult, start: Int, end: Int): List<Rect> {
    val out = ArrayList<Rect>(1)
    var line = -1
    for (i in start until end) {
        val b = l.getBoundingBox(i)
        val at = l.getLineForOffset(i)
        if (at == line) {
            val a = out.last()
            out[out.lastIndex] = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        } else {
            out.add(b)
            line = at
        }
    }
    return out
}

// For each new bit, the old one it is (the longest run of bits the two texts share, in order), or -1.
private fun matches(a: List<Bit>, b: List<Bit>): IntArray {
    val run = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        run[i][j] = if (a[i].key == b[j].key) run[i + 1][j + 1] + 1 else maxOf(run[i + 1][j], run[i][j + 1])
    }
    val out = IntArray(b.size) { -1 }
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) when {
        a[i].key == b[j].key -> { out[j] = i; i++; j++ }
        run[i + 1][j] >= run[i][j + 1] -> i++
        else -> j++
    }
    return out
}

// A number's worth, to roll the right way: minutes, a clock time in minutes, or "now".
private fun worth(s: String): Int? = when {
    s == "now" || s == "עכשיו" -> 0
    s.isNotEmpty() && s.all { it.isDigit() } -> s.toIntOrNull()
    ':' in s -> s.split(':').let { p -> p[0].toIntOrNull()?.let { h -> h * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0) } }
    else -> null
}

private const val SLIDE = 0
private const val ROLL_IN = 1
private const val ROLL_OUT = 2
private const val FADE_IN = 3
private const val FADE_OUT = 4

// How one bit moves: its box (the old text's for what leaves), where it slides from, and a colour it eases from. A
// number that rolls has its worth, and rolls up or down by the one it replaces.
private class Piece(
    val kind: Int, val box: Rect, val from: Offset = Offset.Zero, val tint: Color = Color.Unspecified,
    val to: Color = Color.Unspecified, val mark: Boolean = false, val worth: Int? = null,
) {
    var up: Boolean? = null
}

// cut: what of the new text is drawn by the pieces, not as it stands. shift: the old text's place in the new one's box,
// both held at the start of the line.
private class Plan(val cut: Path, val pieces: List<Piece>, val up: Boolean, val shift: Float)

private fun plan(old: TextLayoutResult, new: TextLayoutResult, ids: Set<String>): Plan {
    val a = bits(old, ids)
    val b = bits(new, ids)
    val match = matches(a, b)
    val shift = if (new.layoutInput.layoutDirection == LayoutDirection.Rtl) (new.size.width - old.size.width).toFloat() else 0f
    val kept = BooleanArray(a.size)
    val pieces = ArrayList<Piece>()
    val cut = Path()
    b.forEachIndexed { j, n ->
        val o = match[j].takeIf { it >= 0 }?.let { kept[it] = true; a[it] }
        val mark = n.mark >= 0
        if (o == null) {
            n.boxes.forEach { pieces.add(Piece(ROLL_IN, it, mark = mark, worth = worth(n.key))) }
        } else if (o.boxes.size == 1 && n.boxes.size == 1) {
            val box = n.boxes[0]
            val from = o.boxes[0].topLeft + Offset(shift, 0f) - box.topLeft
            val recolour = o.color != n.color
            if (abs(from.x) < .5f && abs(from.y) < .5f && !recolour) return@forEachIndexed
            if (abs(from.y) < box.height / 2) {
                pieces.add(Piece(SLIDE, box, from, if (recolour) o.color else Color.Unspecified, n.color, mark))
            } else {
                pieces.add(Piece(FADE_IN, box, mark = mark))
                if (!mark) pieces.add(Piece(FADE_OUT, o.boxes[0].translate(shift, 0f)))
            }
        } else {
            n.boxes.forEach { pieces.add(Piece(FADE_IN, it, mark = mark)) }
            if (!mark) o.boxes.forEach { pieces.add(Piece(FADE_OUT, it.translate(shift, 0f))) }
        }
        n.boxes.forEach { cut.addRect(it) }
    }
    a.forEachIndexed { i, o ->
        if (!kept[i] && o.mark < 0) o.boxes.forEach { pieces.add(Piece(ROLL_OUT, it.translate(shift, 0f), worth = worth(o.key))) }
    }
    // Each new number rolls the way it went from the old one most in its place; the rest the way the first did.
    val outs = pieces.filter { it.kind == ROLL_OUT && it.worth != null }.toMutableList()
    for (q in pieces) {
        if (q.kind != ROLL_IN || q.worth == null) continue
        val o = outs.maxByOrNull { overlap(it.box, q.box) }?.takeIf { overlap(it.box, q.box) > 0f } ?: continue
        outs.remove(o)
        q.up = q.worth >= o.worth!!
        o.up = q.up
    }
    val gone = a.filterIndexed { i, o -> !kept[i] && o.mark < 0 }.firstNotNullOfOrNull { worth(it.key) }
    val come = b.filterIndexed { j, n -> match[j] < 0 && n.mark < 0 }.firstNotNullOfOrNull { worth(it.key) }
    return Plan(cut, pieces, gone == null || come == null || come >= gone, shift)
}

// How far two boxes on one line share their width.
private fun overlap(a: Rect, b: Rect): Float =
    if (abs(a.top - b.top) >= a.height / 2) 0f else (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)

private fun ContentDrawScope.drawMorph(m: Morph, t: Float) {
    val old = m.was
    val new = m.now
    if (t >= 1f || old == null || new == null) {
        drawContent()
        return
    }
    val p = m.plan ?: plan(old, new, m.ids).also { m.plan = it }
    val move = FastOutSlowInEasing.transform(t)
    val fadeIn = LinearOutSlowInEasing.transform((t * MORPH_MS / 220f).coerceAtMost(1f))
    val fadeOut = 1f - FastOutLinearInEasing.transform((t * MORPH_MS / 150f).coerceAtMost(1f))
    val oldAt = Offset(p.shift, 0f)
    clipPath(p.cut, ClipOp.Difference) { this@drawMorph.drawContent() }
    for (q in p.pieces) {
        val rise = q.box.height * .5f
        val box = q.box
        val dir = if (q.up ?: p.up) 1f else -1f
        when (q.kind) {
            SLIDE -> translate(q.from.x * (1 - move), q.from.y * (1 - move)) {
                clipRect(box.left, box.top, box.right, box.bottom) {
                    when {
                        q.mark -> this@drawMorph.drawContent()
                        q.tint.isSpecified -> recolour(new, box, lerp(q.tint, q.to, move), m.paint)
                        else -> drawText(new)
                    }
                }
            }
            ROLL_IN -> translate(0f, dir * rise * (1 - move)) {
                faded(box, fadeIn, m.paint) { if (q.mark) this@drawMorph.drawContent() else drawText(new) }
            }
            ROLL_OUT -> translate(0f, -dir * rise * move) { faded(box, fadeOut, m.paint) { drawText(old, topLeft = oldAt) } }
            FADE_IN -> faded(box, fadeIn, m.paint) { if (q.mark) this@drawMorph.drawContent() else drawText(new) }
            FADE_OUT -> faded(box, fadeOut, m.paint) { drawText(old, topLeft = oldAt) }
        }
    }
}

// Draws one bit at an alpha: a layer of its own, as spans keep their own colours whatever alpha the text is drawn at.
private inline fun DrawScope.faded(box: Rect, alpha: Float, paint: Paint, block: DrawScope.() -> Unit) {
    if (alpha <= 0f) return
    paint.alpha = alpha
    drawContext.canvas.saveLayer(box, paint)
    clipRect(box.left, box.top, box.right, box.bottom) { block() }
    drawContext.canvas.restore()
}

// Draws one bit in another colour: its glyphs, then the colour over them only.
private fun DrawScope.recolour(l: TextLayoutResult, box: Rect, color: Color, paint: Paint) {
    paint.alpha = 1f
    drawContext.canvas.saveLayer(box, paint)
    drawText(l)
    drawRect(color, box.topLeft, box.size, blendMode = BlendMode.SrcIn)
    drawContext.canvas.restore()
}
