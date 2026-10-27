package com.pixelpass.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixelpass.core.PixelPassLog
import com.pixelpass.utils.FaceAnalyzer
import com.pixelpass.utils.FaceDetectionStatus
import java.util.concurrent.Executors

/** The CameraX use cases one face preview owns, so disposal never unbinds another screen's camera. */
private class FaceUseCases {
    var preview: Preview? = null
    var analysis: ImageAnalysis? = null

    fun unbindFrom(provider: ProcessCameraProvider?) {
        val owned = listOfNotNull(preview, analysis).toTypedArray()
        preview = null
        analysis = null
        if (provider == null || owned.isEmpty()) return
        runCatching { provider.unbind(*owned) }
            .onFailure { PixelPassLog.w(PixelPassLog.CAMERA, "Face preview unbind failed", it) }
    }
}

/**
 * Live camera preview that reports whether a face is inside the oval and captures only when
 * asked to.
 *
 * The guard owns the shutter: increment [captureRequest] to take the frame that is on screen
 * right now. [onFaceReady] stays `true` only while one face has held the oval for a few
 * frames, which is what keeps the capture button enabled.
 */
@Composable
fun FaceCameraPreview(
    modifier: Modifier = Modifier,
    captureRequest: Int = 0,
    onStatusUpdate: (FaceDetectionStatus) -> Unit = {},
    onFaceReady: (Boolean) -> Unit = {},
    onCaptureMissed: () -> Unit = {},
    onFaceDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // The camera outlives recompositions, so the callbacks are read through state holders
    // instead of being captured once.
    val currentOnStatusUpdate by rememberUpdatedState(onStatusUpdate)
    val currentOnFaceReady by rememberUpdatedState(onFaceReady)
    val currentOnCaptureMissed by rememberUpdatedState(onCaptureMissed)
    val currentOnFaceDetected by rememberUpdatedState(onFaceDetected)

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analyzerState = remember { mutableStateOf<FaceAnalyzer?>(null) }
    val providerState = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val ownedUseCases = remember { FaceUseCases() }

    DisposableEffect(Unit) {
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                providerState.value = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Face capture runs on the rear camera, which is what the demo
                // terminals are pointed at; the front one stays as a fallback.
                val useFrontCamera = !cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val faceAnalyzer = FaceAnalyzer(
                    onStatusUpdate = { status ->
                        mainExecutor.execute { currentOnStatusUpdate(status) }
                    },
                    onFaceExtracted = { faceBase64 ->
                        mainExecutor.execute { currentOnFaceDetected(faceBase64) }
                    },
                    onReadyChanged = { ready ->
                        mainExecutor.execute { currentOnFaceReady(ready) }
                    },
                    onCaptureMissed = {
                        mainExecutor.execute { currentOnCaptureMissed() }
                    },
                    // Only a front camera previews mirrored, so only then is the analysed
                    // frame mirrored as well.
                    mirrorHorizontally = useFrontCamera
                )
                analyzerState.value = faceAnalyzer

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, faceAnalyzer)
                    }

                // Taking the camera over from the scanner is deliberate; releasing it again
                // must not touch the destination that is already coming in behind us.
                cameraProvider.unbindAll()
                ownedUseCases.preview = preview
                ownedUseCases.analysis = imageAnalyzer
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
                PixelPassLog.i(
                    PixelPassLog.CAMERA,
                    "Face preview bound (${if (useFrontCamera) "front" else "rear"} camera)"
                )
            } catch (e: Exception) {
                PixelPassLog.e(PixelPassLog.CAMERA, "Face preview could not bind the camera", e)
            }
        }, mainExecutor)

        onDispose {
            cameraExecutor.shutdown()
            analyzerState.value?.close()
            analyzerState.value = null
            ownedUseCases.unbindFrom(providerState.value)
            providerState.value = null
            PixelPassLog.i(PixelPassLog.CAMERA, "Face preview disposed; released only its own use cases")
        }
    }

    // The shutter: the screen bumps the counter and the analyser grabs the next aligned frame.
    LaunchedEffect(captureRequest) {
        if (captureRequest <= 0) return@LaunchedEffect
        val analyzer = analyzerState.value
        if (analyzer == null) {
            PixelPassLog.w(PixelPassLog.CAMERA, "Capture request #$captureRequest ignored: camera not ready")
            currentOnCaptureMissed()
            return@LaunchedEffect
        }
        val accepted = analyzer.requestCapture()
        PixelPassLog.i(
            PixelPassLog.CAMERA,
            "Capture request #$captureRequest ${if (accepted) "accepted" else "rejected (no aligned face)"}"
        )
        if (!accepted) currentOnCaptureMissed()
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
