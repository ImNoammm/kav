package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/*
 * Moovit's own vehicle marks, not redrawings of them.
 *
 * TransitType.VehicleType names one drawable per vehicle, ic_transit_type_tram /
 * _subway / _rail / _bus / _ferry / _cable_car / _gondola / _funicular, and this is
 * the pathData out of those eight files, unaltered, on their shared 26x26 viewport.
 * Kav used to hand-draw five geometric approximations and route eight vehicles into
 * them, so a funicular, a gondola and a light rail all came out as the same tram box.
 * Copying the geometry costs a few kilobytes of string and ends the guessing: the
 * Carmelit gets the funicular Moovit draws for it, the Rakavlit the cable car.
 *
 * Only the colour is Kav's. Moovit fills these with ?colorOnSurface and Kav fills them
 * with its own greys (D11), which is the same relationship the palette already has to
 * every other Moovit mark.
 */
private val MODE_PATHS: Map<Mode, List<String>> = mapOf(
    Mode.TRAM to listOf(
        "M15 0h-4C9.528 0 8.238 0.808 7.544 2H8.77C9.32 1.388 10.115 1 11 1h4c0.885 0 1.68 0.388 2.23 1h1.226C17.762 0.808 16.472 0 15 0z",
        "M14.5 0.5h1l-2 3.5h-1z",
        "M11.5 0.5h-1l2 3.5h1zM17 4H9C6.791 4 5 5.791 5 8v9c0 2.209 3.582 4 8 4s8-1.791 8-4V8c0-2.209-1.791-4-4-4zM8 17c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm10 0c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm1-5s0 2-6 2-6-2-6-2V8c0-1.105 0.895-2 2-2h8c1.105 0 2 0.895 2 2v4zM8.349 21.646L6.004 26h1.704l2.113-3.926zm7.83 0.428L18.292 26h1.704l-2.345-4.354z",
    ),
    Mode.SUBWAY to listOf(
        "M13 4c-4.971 0-9 3.358-9 7.5v5C4 18.847 5.612 21 7.6 21h10.8c1.988 0 3.6-2.153 3.6-4.5v-5C22 7.358 17.971 4 13 4zM7.5 19c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm11 0c-0.552 0-1-0.448-1-1s0.448-1 1-1 1 0.448 1 1-0.448 1-1 1zm1.5-4H6v-3.5C6 8.467 9.14 6 13 6s7 2.467 7 5.5V15zm-3 7l0.536 1H8.464L9 22H7.144L5 26h16l-2.144-4H17zm-9.608 3l0.536-1h10.144l0.536 1H7.392z",
        "M23.5 20.5c1.431-2.089 2.5-4.777 2.5-7.5 0-7.18-5.82-13-13-13S0 5.82 0 13c0 2.723 1.069 5.411 2.5 7.5V17C2.046 15.796 2 14.361 2 13 2 6.935 6.935 2 13 2s11 4.935 11 11c0 1.361-0.046 2.296-0.5 3.5v4z",
    ),
    Mode.TRAIN to listOf(
        "M21 7.177C21 4.623 19.142 3.6 16.569 3.6H16.2V2.8c0-0.442-0.358-0.8-0.8-0.8h-4.8c-0.442 0-0.8 0.358-0.8 0.8v0.8H9.308C6.735 3.6 5 4.623 5 7.177V16.4l1.412 1.592-0.731 0.829c-0.333 0-0.434 0.619-0.132 0.779 0 0 7.367 1.644 7.451 1.6l7.327-1.615c0.302-0.16 0.202-0.767-0.132-0.767L19.4 17.92 21 16.4V7.177zM9.4 18.4c-0.663 0-1.2-0.537-1.2-1.2 0-0.663 0.537-1.2 1.2-1.2 0.663 0 1.2 0.537 1.2 1.2 0 0.663-0.537 1.2-1.2 1.2zm7.2 0c-0.663 0-1.2-0.537-1.2-1.2 0-0.663 0.537-1.2 1.2-1.2 0.663 0 1.2 0.537 1.2 1.2 0 0.663-0.537 1.2-1.2 1.2zm2.4-5.765c0 0.71-2.431 1.714-6 1.714s-6-1.004-6-1.714V7.597C7 6.651 7.48 5.5 8.5 5.5h9c0.995 0 1.5 1.151 1.5 2.097v5.038zM7.5 25h11v1h-11zm0-2h11v1h-11z",
        "M8.349 21.646L6.004 26h1.704l2.113-3.926zm7.83 0.428L18.292 26h1.704l-2.345-4.354z",
    ),
    Mode.BUS to listOf(
        "M22.5 7V6c0-2.303-0.638-4-2.952-4H6.714C4.4 2 3.5 3.697 3.5 6v1H2v4h1.5l0.071 11.429C3.571 23.296 4.275 24 5.143 24H7c0.868 0 1.5-0.632 1.5-1.5V21h9v1.5c0 0.868 0.632 1.5 1.5 1.5h1.857c0.868 0 1.571-0.704 1.571-1.571L22.5 11H24V7h-1.5zM6.714 19.278c-0.864 0-1.571-0.704-1.571-1.564 0-0.86 0.707-1.564 1.571-1.564s1.571 0.704 1.571 1.564c0.001 0.86-0.706 1.564-1.571 1.564zM13 15c-3.866 0-7.5-1.605-7.5-2.381V5.968C5.5 4.934 5.895 4 7 4h12.25c1.105 0 1.25 0.934 1.25 1.968v6.651C20.5 13.395 16.866 15 13 15zm7.857 2.714c0 0.86-0.707 1.564-1.571 1.564s-1.571-0.704-1.571-1.564c0-0.86 0.707-1.564 1.571-1.564s1.571 0.704 1.571 1.564z",
    ),
    Mode.FERRY to listOf(
        "M22.257 10.03C21.812 8.249 20.212 7 18.377 7H15.5V4.5C15.5 4.224 15.276 4 15 4s-0.5 0.224-0.5 0.5V7H11V4.5C11 4.224 10.776 4 10.5 4S10 4.224 10 4.5V7H7.623c-1.835 0-3.435 1.249-3.881 3.03L2.5 13h21l-1.243-2.97zM8.5 11H5.438S6.522 9 7.44 9H8.5v2zm4 0h-3V9h3v2zm4 0h-3V9h3v2zm1 0V9h1.06c0.918 0 2.002 2 2.002 2H17.5zm-8.834 8.5c2.11 0 3.135-0.237 4.221-0.487 1.093-0.252 2.222-0.513 4.446-0.513 2.224 0 3.354 0.261 4.446 0.513 0.433 0.1 0.86 0.197 1.347 0.278L26 14H1l-1 4.5c2.223 0 3.353 0.261 4.446 0.513 1.085 0.25 2.11 0.487 4.22 0.487zm14.459 1.291c-0.487-0.081-0.913-0.178-1.347-0.278C20.687 20.261 19.557 20 17.333 20s-3.353 0.261-4.446 0.513C11.801 20.763 10.776 21 8.666 21c-2.109 0-3.135-0.237-4.22-0.487C3.353 20.261 2.223 20 0 20v1c2.11 0 3.135 0.237 4.22 0.487C5.313 21.739 6.442 22 8.666 22c2.224 0 3.354-0.261 4.446-0.513C14.198 21.237 15.223 21 17.333 21s3.135 0.237 4.221 0.487C22.646 21.739 22.776 22 25 22v-1c-1.268 0-1.141-0.086-1.875-0.209z",
    ),
    Mode.CABLE to listOf(
        "M5 26h1.856L9 22H7.144zm12-4l2.144 4H21l-2.144-4zM4.5 20h17v1h-17zM23 10v8h-1V5c0.552 0 1-0.448 1-1s-0.448-1-1-1h-4.5V2H18c0-1.105-2.239-2-5-2S8 0.895 8 2h0.5v1H4C3.448 3 3 3.448 3 4s0.448 1 1 1v13H3v-8H2v9h22v-9h-1zm-3-5v6h-4V5h4zm-6.035 10.25C13.853 15.681 13.465 16 13 16c-0.465 0-0.853-0.319-0.965-0.75C12.015 15.17 12 15.087 12 15c0-0.552 0.448-1 1-1s1 0.448 1 1c0 0.087-0.015 0.17-0.035 0.25zM11 11V5h4v6h-4zm-1-6v6H6V5h4z",
    ),
    Mode.GONDOLA to listOf(
        "M12.5 2.5h1V7h-1z",
        "M12.5 2.5h1v6h-1z",
        "M12.5 7.5h1l-2 2.5h-1z",
        "M13.5 7.5h-1l2 2.5h1zM22 1.01L4 5V3.99L22 0zM18 10H8c-2.2 0-5 2.287-5 4.471v5.967C3 22.352 4.484 24.094 6.619 25c0.932 0.458 1.294 1 3.381 1h6c2.087 0 2.449-0.542 3.381-1C21.516 24.094 23 22.352 23 20.438v-5.967C23 12.287 20.2 10 18 10zm3 4.471V18h-4v-6h1c1.215 0 3 1.513 3 2.471zM16 12v6h-6v-6h6zM5 14.471C5 13.513 6.785 12 8 12h1v6H5v-3.529z",
    ),
    Mode.FUNICULAR to listOf(
        "M23 6.799C23 4.673 21.159 3 18.889 3H7.111C4.841 3 3 4.673 3 6.799v12.849L23 15.7V6.799zM21 13H5V7.121C5 6.044 5.947 5 7.111 5h11.778C20.053 5 21 6.044 21 7.121V13z",
        "M9 4.958h1V5H9zM9 5h1v8H9zm7-0.042h1V5h-1zM16 5h1v8h-1zm8 12.021l-22 4.5v1.958l22-4.5z",
    ),
)

/** Moovit's viewportWidth/viewportHeight for every ic_transit_type_* drawable. */
private const val VIEWPORT = 26f

private val pathCache = HashMap<Mode, List<Path>>()
private val markCache = HashMap<Triple<Mode, Int, ULong>, ImageBitmap>()

private fun pathsFor(mode: Mode): List<Path>? = MODE_PATHS[mode]?.let { data ->
    pathCache.getOrPut(mode) { data.map { PathParser().parsePathString(it).toPath() } }
}

/**
 * The mode's mark rasterised once, [px] pixels square and filled with [ink], on
 * nothing. The map draws it in the middle of a coloured disc, so the ink is the
 * background colour and the disc reads as the vehicle's own colour around it.
 *
 * One cache serves both drawing paths: MapLibre's symbol layer wants an Android
 * bitmap, a Compose canvas wants an ImageBitmap, and a bus painted by the GL layer
 * has to be the same bus the Live tab paints over its own map.
 */
fun modeMark(mode: Mode, px: Int, ink: Color = K.bg): ImageBitmap =
    markCache.getOrPut(Triple(mode, px, ink.value)) {
        val image = ImageBitmap(px, px)
        val paths = pathsFor(mode)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image),
            Size(px.toFloat(), px.toFloat()),
        ) {
            if (paths != null) {
                scale(px / VIEWPORT, px / VIEWPORT, Offset.Zero) { paths.forEach { drawPath(it, ink) } }
            } else {
                drawFallback(mode, ink)
            }
        }
        image
    }

/** The name [modeMark] is registered under on a map style, one per vehicle. */
fun modeIconName(mode: Mode): String = "kav-mode-${mode.name.lowercase(java.util.Locale.US)}"

/** The same mark, centred on [centre], for the canvases that paint their own vehicles. */
fun DrawScope.drawModeMark(mode: Mode, centre: Offset, span: Float, ink: Color = K.bg) {
    val px = span.toInt().coerceAtLeast(4)
    drawImage(modeMark(mode, px, ink), topLeft = Offset(centre.x - px / 2f, centre.y - px / 2f))
}

/**
 * A vehicle mark, filled with [tint]. Moovit's geometry for the eight vehicles it
 * names; Kav's own for the two it does not, a share taxi, and the circle that stands
 * in for a route type no feed here uses.
 */
@Composable
fun ModeGlyph(mode: Mode, tint: Color = K.dim, size: Dp = 13.dp) {
    val paths = remember(mode) { pathsFor(mode) }
    Canvas(Modifier.size(size)) {
        if (paths != null) {
            scale(this.size.width / VIEWPORT, this.size.height / VIEWPORT, Offset.Zero) {
                paths.forEach { drawPath(it, tint) }
            }
        } else {
            drawFallback(mode, tint)
        }
    }
}

/**
 * The two marks Moovit has no vehicle type for. A share taxi is route type 8 in the
 * Israeli feed and 715 for the demand-responsive shuttles, and neither is a
 * TransitType.VehicleType, so the car below is Kav's: a cabin over a body, wheels
 * under it, and the roof sign that makes it a taxi rather than a briefcase.
 */
private fun DrawScope.drawFallback(mode: Mode, tint: Color) {
    val w = size.width
    val h = size.height
    val sw = w * 0.11f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    if (mode == Mode.TAXI) {
        line(.08f, .74f, .92f, .74f); line(.08f, .54f, .08f, .74f)
        line(.92f, .54f, .92f, .74f); line(.08f, .54f, .26f, .54f)
        line(.26f, .54f, .34f, .34f); line(.34f, .34f, .68f, .34f)
        line(.68f, .34f, .78f, .54f); line(.78f, .54f, .92f, .54f)
        drawCircle(tint, w * .07f, Offset(w * .28f, h * .80f))
        drawCircle(tint, w * .07f, Offset(w * .72f, h * .80f))
        line(.40f, .34f, .40f, .22f); line(.62f, .34f, .62f, .22f)
        line(.40f, .22f, .62f, .22f)
    } else {
        drawCircle(tint, radius = w * .30f, style = Stroke(width = sw))
    }
}

/**
 * A stop's mark: Moovit's img_general_station_* is a filled plate carrying the vehicle
 * in the plate's negative space, and that is what this draws, the same relationship
 * [RailMark] already has to the Israel Railways badge, extended to every vehicle so a
 * railway station stops reading as bare text next to its name. Moovit rounds every
 * plate but the subway's, which is a circle.
 */
@Composable
fun StationMark(mode: Mode, size: Dp = 16.dp, plate: Color = K.text, on: Color = K.surface1) {
    val paths = remember(mode) { pathsFor(mode) }
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (mode == Mode.SUBWAY) {
            drawCircle(plate, radius = w / 2f, center = Offset(w / 2f, h / 2f))
        } else {
            drawRoundRect(
                plate,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .22f),
            )
        }
        // the vehicle sits inside the plate at Moovit's own proportions, inset so the
        // plate reads as a plate rather than a frame the glyph is touching
        val inset = w * .16f
        val span = w - inset * 2f
        scale(span / VIEWPORT, span / VIEWPORT, Offset.Zero) {
            translate(inset * VIEWPORT / span, inset * VIEWPORT / span) {
                if (paths != null) paths.forEach { drawPath(it, on) } else drawFallback(mode, on)
            }
        }
    }
}
