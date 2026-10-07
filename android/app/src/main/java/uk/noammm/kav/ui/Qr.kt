package uk.noammm.kav.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Size as PixelSize

// The back camera, read for a QR code until one decodes; onCode gets its text once, on the main thread, and onFail
// hears when the camera would not start.
@Composable
internal fun QrScanner(modifier: Modifier, onFail: () -> Unit = {}, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onLatestCode = rememberUpdatedState(onCode)
    val onLatestFail = rememberUpdatedState(onFail)
    val worker = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val gone = remember { AtomicBoolean(false) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }
    DisposableEffect(Unit) {
        onDispose {
            gone.set(true)
            runCatching { if (providerFuture.isDone) providerFuture.get().unbindAll() }
            worker.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            PreviewView(c).also { view ->
                providerFuture.addListener({
                    // Scanning was left before the camera was ready: it stays off.
                    if (gone.get()) return@addListener
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val reader = QRCodeReader()
                    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
                    // CameraX analyses 640x480 by default, too few pixels for a bus sticker beyond arm's length.
                    val sharp = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                        PixelSize(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )).build()
                    val analysis = ImageAnalysis.Builder().setResolutionSelector(sharp)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(worker) { image ->
                        image.use {
                            if (done.get()) return@use
                            // The luminance plane is all a QR needs, and a QR reads at any rotation.
                            val plane = it.planes[0]
                            val bytes = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
                            val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
                            // Glare and light-on-dark prints defeat one binarizer more often than all three tries.
                            val text = sequenceOf(
                                { BinaryBitmap(HybridBinarizer(source)) },
                                { BinaryBitmap(GlobalHistogramBinarizer(source)) },
                                { BinaryBitmap(HybridBinarizer(source.invert())) },
                            ).firstNotNullOfOrNull { bitmap ->
                                runCatching { reader.decode(bitmap(), hints).text }.getOrNull().also { reader.reset() }
                            }
                            if (!text.isNullOrBlank() && done.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(c).execute { if (!gone.get()) onLatestCode.value(text) }
                            }
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { onLatestFail.value() }
                }, ContextCompat.getMainExecutor(c))
            }
        },
    )
}

// A ticket's QR as dark squares on white. The writer leaves the four-square quiet border scanners need.
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull() }
    Canvas(modifier.aspectRatio(1f).background(Color.White)) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
