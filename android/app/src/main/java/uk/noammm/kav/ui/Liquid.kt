package uk.noammm.kav.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

// The AGSL shaders below are from Kyant0's AndroidLiquidGlass (Apache License 2.0, Copyright 2025 Kyant).

// Over the bare page a pane comes out the cards' own shade, with what passes under it showing through.
internal val liquidTint: Color get() {
    val a = .72f
    fun over(card: Float, page: Float) = ((card - (1 - a) * page) / a).coerceIn(0f, 1f)
    return Color(over(K.surface.red, K.bg.red), over(K.surface.green, K.bg.green), over(K.surface.blue, K.bg.blue), a)
}

class LiquidBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
internal fun rememberLiquidLayer(): LiquidBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { LiquidBackdrop(layer) }
}

internal fun Modifier.liquidSource(backdrop: LiquidBackdrop): Modifier = this
    .onPlaced { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

@Composable
internal fun Modifier.liquidPane(backdrop: LiquidBackdrop, radius: Dp): Modifier {
    val pane = rememberGraphicsLayer()
    val lens = remember { RuntimeShader(LIQUID_REFRACTION) }
    var at by remember { mutableStateOf(Offset.Zero) }
    return this
        .onPlaced { at = it.positionInRoot() }
        .clip(RoundedCornerShape(radius))
        .drawBehind {
            val r = radius.toPx().coerceAtMost(size.minDimension / 2)
            val dispersion = .25f
            val blur = 4.dp.toPx()
            val band = (size.minDimension * .3f).coerceIn(24.dp.toPx(), 48.dp.toPx())
            val reach = (size.minDimension * .65f).coerceIn(52.dp.toPx(), 110.dp.toPx())
            // A shader only gets the pixels inside its clip, so the lens bends inward.
            val pad = blur * 3 + reach
            lens.setFloatUniform("size", size.width, size.height)
            lens.setFloatUniform("offset", -pad, -pad)
            lens.setFloatUniform("cornerRadii", r, r, r, r)
            lens.setFloatUniform("refractionHeight", band)
            lens.setFloatUniform("refractionAmount", -reach)
            lens.setFloatUniform("depthEffect", 1f)
            lens.setFloatUniform("chromaticAberration", dispersion)
            pane.renderEffect = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(lens, "content"),
                RenderEffect.createChainEffect(
                    RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP),
                    RenderEffect.createColorFilterEffect(
                        ColorMatrixColorFilter(
                            ColorMatrix().apply { setSaturation(if (K.light) 1.3f else 1.1f) },
                        ),
                    ),
                ),
            ).asComposeRenderEffect()
            val away = at - backdrop.origin
            val padded = IntSize((size.width + 2 * pad).roundToInt(), (size.height + 2 * pad).roundToInt())
            pane.record(size = padded) {
                drawRect(K.bg)
                translate(pad - away.x, pad - away.y) { drawLayer(backdrop.layer) }
            }
            translate(-pad, -pad) { drawLayer(pane) }
            drawRect(liquidTint)
        }
}

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

private const val LIQUID_REFRACTION = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}"""
