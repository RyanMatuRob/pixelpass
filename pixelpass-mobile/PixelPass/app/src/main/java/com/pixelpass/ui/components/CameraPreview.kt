package com.pixelpass.ui.components

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixelpass.core.PixelPassLog
import com.pixelpass.utils.QrCodeAnalyzer
import java.util.concurrent.Executors

/**
 * The CameraX use cases one preview instance owns.
 *
 * Disposal releases only these, because the incoming navigation destination has
 * already bound its own camera by the time the transition finishes; a blanket
 * `unbindAll()` there would tear down the live preview of the next screen.
 */
private class OwnedUseCases {
    var preview: Preview? = null
    var analysis: ImageAnalysis? = null

    fun unbindFrom(provider: ProcessCameraProvider?) {
        val owned = listOfNotNull(preview, analysis).toTypedArray()
        preview = null
        analysis = null
        if (provider == null || owned.isEmpty()) return
        runCatching { provider.unbind(*owned) }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    isTorchOn: Boolean = false,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val ownedUseCases = remember { OwnedUseCases() }

    // Initialize CameraProvider once
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, mainExecutor)
    }

    // Unbind and rebind camera whenever cameraProvider or lensFacing changes
    LaunchedEffect(cameraProvider, lensFacing) {
        val provider = cameraProvider ?: return@LaunchedEffect

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    cameraExecutor,
                    QrCodeAnalyzer { jwt ->
                        mainExecutor.execute { onQrScanned(jwt) }
                    }
                )
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            ownedUseCases.preview = preview
            ownedUseCases.analysis = imageAnalyzer
            val boundCamera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            camera = boundCamera
            PixelPassLog.i(PixelPassLog.CAMERA, "Scanner preview bound (lens=$lensFacing)")

            // Apply initial torch state if camera supports flash
            if (boundCamera.cameraInfo.hasFlashUnit()) {
                boundCamera.cameraControl.enableTorch(isTorchOn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Dynamically toggle torch light whenever isTorchOn or camera instance changes
    LaunchedEffect(isTorchOn, camera) {
        val currentCamera = camera
        if (currentCamera != null && currentCamera.cameraInfo.hasFlashUnit()) {
            currentCamera.cameraControl.enableTorch(isTorchOn)
        }
    }

    // Clean up resources on disposal
    DisposableEffect(Unit) {
        onDispose {
            // Release only this preview's own use cases. The next destination binds its
            // camera before this screen is disposed, so unbindAll() would kill it.
            ownedUseCases.unbindFrom(cameraProvider)
            cameraExecutor.shutdown()
            PixelPassLog.i(PixelPassLog.CAMERA, "Scanner preview disposed")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
