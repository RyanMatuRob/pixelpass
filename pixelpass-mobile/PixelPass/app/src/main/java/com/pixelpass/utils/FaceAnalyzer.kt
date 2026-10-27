package com.pixelpass.utils

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.pixelpass.core.PixelPassLog
import java.io.ByteArrayOutputStream

enum class FaceDetectionStatus {
    NO_FACE,
    OFF_CENTER,
    TOO_FAR,
    ALIGNED
}

/**
 * Watches the camera and reports whether one face is sitting inside the capture oval.
 *
 * Nothing is captured on its own any more: the guard decides when the frame is taken.
 * [FaceDetectionStatus.ALIGNED] means the face is in the oval right now, and
 * [onReadyChanged] flips to `true` only once that has held for [READY_FRAMES] frames,
 * which is what enables the capture button.
 */
class FaceAnalyzer(
    private val onStatusUpdate: (FaceDetectionStatus) -> Unit,
    private val onFaceExtracted: (String) -> Unit,
    private val onReadyChanged: (Boolean) -> Unit = {},
    private val onCaptureMissed: () -> Unit = {},
    private val mirrorHorizontally: Boolean = false
) : ImageAnalysis.Analyzer {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()

    private val detector = FaceDetection.getClient(options)
    private var consecutiveAlignedFrames = 0
    private var lastStatus: FaceDetectionStatus? = null

    // Read from the UI thread (`requestCapture`) and written from the analysis callbacks.
    @Volatile
    private var isReady = false

    @Volatile
    private var captureRequested = false

    private var captureDeadlineNanos = 0L

    /**
     * Asks for the next aligned frame to be cropped and handed to [onFaceExtracted].
     *
     * Returns `false` when no face was ready at the moment of the tap, so the caller can
     * tell the guard instead of silently capturing nothing.
     */
    fun requestCapture(): Boolean {
        if (!isReady) return false
        captureRequested = true
        captureDeadlineNanos = System.nanoTime() + CAPTURE_WINDOW_NANOS
        return true
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.size == 1) {
                    evaluate(faces.first(), imageProxy)
                } else {
                    consecutiveAlignedFrames = 0
                    publishStatus(FaceDetectionStatus.NO_FACE)
                    publishReady(false)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun evaluate(face: Face, imageProxy: ImageProxy) {
        val box = face.boundingBox.toFaceBox()
        val frame = FaceGeometry.uprightFrameSize(
            bufferWidth = imageProxy.width,
            bufferHeight = imageProxy.height,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees
        )
        val status = FaceGeometry.status(box, frame.width, frame.height)

        if (status == FaceDetectionStatus.ALIGNED) {
            if (consecutiveAlignedFrames < READY_FRAMES) consecutiveAlignedFrames++
        } else {
            consecutiveAlignedFrames = 0
        }

        publishStatus(status)
        publishReady(consecutiveAlignedFrames >= READY_FRAMES)

        if (!captureRequested) return

        if (status != FaceDetectionStatus.ALIGNED) {
            // The face slipped out of the oval after the tap. Hold the request briefly so a
            // momentary wobble does not waste the tap, then give up out loud.
            if (System.nanoTime() > captureDeadlineNanos) {
                captureRequested = false
                PixelPassLog.w(
                    PixelPassLog.CAMERA,
                    "Capture missed: no aligned face inside the oval within the request window"
                )
                consumeReadyState()
                onCaptureMissed()
            }
            return
        }

        captureRequested = false
        val bitmap = imageProxyToBitmap(imageProxy)
        val croppedFace = bitmap?.let { cropFace(it, box) }

        if (croppedFace == null) {
            PixelPassLog.w(PixelPassLog.CAMERA, "Capture missed: the face could not be cropped")
            consumeReadyState()
            onCaptureMissed()
            return
        }

        // Readiness is consumed by the capture: the guard has to see the oval confirmed
        // again before a second frame can be taken.
        consumeReadyState()
        val base64 = bitmapToBase64(croppedFace)
        PixelPassLog.i(
            PixelPassLog.CAMERA,
            "Face captured on request (${croppedFace.width}x${croppedFace.height}, ${base64.length} chars)"
        )
        onFaceExtracted(base64)
    }

    /** Drops the current confirmation so the next capture needs a fresh, stable alignment. */
    private fun consumeReadyState() {
        consecutiveAlignedFrames = 0
        publishReady(false)
    }

    private fun cropFace(bitmap: Bitmap, box: FaceBox): Bitmap? =
        FaceGeometry
            .cropRect(box, bitmap.width, bitmap.height, mirrored = mirrorHorizontally)
            ?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width, it.height) }

    private fun Rect.toFaceBox() = FaceBox(left, top, right, bottom)

    private fun publishStatus(status: FaceDetectionStatus) {
        // One status per change: the preview would otherwise rewrite the status chip 30x a second.
        if (status == lastStatus) return
        lastStatus = status
        onStatusUpdate(status)
    }

    private fun publishReady(ready: Boolean) {
        if (ready == isReady) return
        isReady = ready
        onReadyChanged(ready)
    }

    /** Releases the ML Kit detector; the camera executor is owned by the preview. */
    fun close() {
        runCatching { detector.close() }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, CAPTURE_JPEG_QUALITY, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()

        val rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        val matrix = Matrix()
        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        if (mirrorHorizontally) {
            matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
        }

        return Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    }

    private companion object {
        const val READY_FRAMES = 3
        const val CAPTURE_JPEG_QUALITY = 80

        // A tap is honoured for this long, so a face that leaves the oval for a heartbeat
        // still lands the capture instead of silently doing nothing.
        val CAPTURE_WINDOW_NANOS = 1_500_000_000L
    }
}
