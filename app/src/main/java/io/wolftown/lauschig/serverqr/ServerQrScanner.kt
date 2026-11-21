package io.wolftown.lauschig.serverqr

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.wolftown.lauschig.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera page of the setup form: shows what the camera sees and hands the text of the first QR code it finds to
 * [onCode], whatever that code is ([ServerQr.read] decides). The pictures stay in memory for the moment they are looked
 * at: none is stored or sent, and nothing here writes to the log. Without the camera permission (the form asks for it
 * before it opens this page) or without a usable camera it says so and can only be closed. [hint] and [noCameraHint]
 * say where the code is and what else to do; „Fernseher einrichten“ scans a TV's code with the same page.
 */
@Composable
fun ServerQrScanner(
    onCode: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes hint: Int = R.string.server_qr_scan_hint,
    @StringRes noCameraHint: Int = R.string.server_qr_scan_no_camera,
) {
    val context = LocalContext.current
    val allowed = remember { ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    var failed by remember { mutableStateOf(!allowed) }
    BackHandler(onBack = onClose)
    Box(modifier.fillMaxSize().background(Color.Black).testTag("qrScanner")) {
        if (!failed) CameraFrames(onCode, onFailed = { failed = true }, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("qrScannerClose")) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.server_qr_scan_close), tint = Color.White)
                }
                Text(stringResource(R.string.server_qr_scan), style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
            Box(Modifier.weight(1f))
            Text(
                stringResource(if (failed) noCameraHint else hint),
                style = MaterialTheme.typography.bodyMedium, color = Color.White,
                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp).testTag("qrScannerHint"),
            )
        }
    }
}

/** The preview and the frame reader, bound to the page's lifecycle; both end with the page. */
@Composable
private fun CameraFrames(onCode: (String) -> Unit, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val code by rememberUpdatedState(onCode)
    val failed by rememberUpdatedState(onFailed)
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val frames = Executors.newSingleThreadExecutor()
        val decoder = QrFrameDecoder()
        val found = AtomicBoolean(false)
        var gone = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        // 1280 x 720 where the camera has it (the default of 640 x 480 leaves a code held at arm's length two or three
        // pixels a module); a frame the reader is still busy with is skipped.
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(frames) { image ->
            // One code is enough: the page closes with it.
            val text = image.use { if (found.get()) null else decoder.decode(it) }
            if (text != null && found.compareAndSet(false, true)) main.execute { if (!gone) code(text) }
        }
        val pending = ProcessCameraProvider.getInstance(context)
        pending.addListener({
            if (gone) return@addListener
            try {
                val cameras = pending.get()
                val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA).firstOrNull { cameras.hasCamera(it) }
                if (selector == null) {
                    failed()
                } else {
                    cameras.bindToLifecycle(owner, selector, preview, analysis)
                    provider = cameras
                }
            } catch (_: Exception) {
                // No camera service, the camera in use by something else, a device policy: the form still works by hand.
                failed()
            }
        }, main)
        onDispose {
            gone = true
            // The camera first, so no frame is on its way when the reader's thread ends.
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            frames.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

/** The frame's grey plane (Y of YUV_420_888), which is all a QR code needs. */
private fun QrFrameDecoder.decode(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return decode(bytes, plane.rowStride, image.width, image.height)
}
