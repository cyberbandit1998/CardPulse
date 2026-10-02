package io.github.cyberbandit1998.cardpulse.camera

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/** Lets the UI trigger a photo from the camera that [CameraPreview] has bound. */
class CaptureController {
    internal var capture: ((File, (Result<File>) -> Unit) -> Unit)? = null

    val isReady: Boolean get() = capture != null

    fun take(file: File, done: (Result<File>) -> Unit) {
        val action = capture
        if (action == null) done(Result.failure(IllegalStateException("The camera isn't ready yet."))) else action(file, done)
    }
}

/**
 * A full-screen camera preview with a card-shaped guide. The photo that is saved is the whole frame; the
 * guide only helps the user fill it with the card. About 3 megapixels is plenty: the server shrinks photos
 * to 2048 pixels on the long side anyway, and small files upload quickly on mobile data.
 */
@Composable
fun CameraPreview(controller: CaptureController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val executor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false

        providerFuture.addListener({
            // The screen may have been left before the camera was ready; don't bind one nobody is looking at.
            if (disposed) return@addListener
            val cameraProvider = providerFuture.get()
            provider = cameraProvider

            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(2048, 1536),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)

            controller.capture = { file, done ->
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            done(Result.success(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            file.delete()
                            done(Result.failure(exception))
                        }
                    },
                )
            }
        }, executor)

        onDispose {
            disposed = true
            controller.capture = null
            provider?.unbindAll()
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        CardGuide(Modifier.fillMaxSize())
    }
}

/** A rounded rectangle with a playing card's proportions (63 x 88 mm), centred in the view. */
@Composable
internal fun CardGuide(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val ratio = 63f / 88f
        val maxWidth = size.width * 0.78f
        val maxHeight = size.height * 0.72f
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f - size.height * 0.04f),
            size = GeoSize(width, height),
            cornerRadius = CornerRadius(14.dp.toPx()),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }
}
