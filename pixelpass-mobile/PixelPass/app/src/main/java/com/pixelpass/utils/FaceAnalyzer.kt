package com.pixelpass.utils

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream

class FaceAnalyzer(private val onFaceExtracted: (String) -> Unit) : ImageAnalysis.Analyzer {

    // We only need the bounding box now, so we can use the absolute fastest settings
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()

    private val detector = FaceDetection.getClient(options)
    private var isAnalyzing = true

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isAnalyzing) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    // Only process if exactly ONE face is in frame (prevents scanning people in the background)
                    if (faces.size == 1) {
                        val face = faces.first()
                        val boundingBox = face.boundingBox

                        // Ensure the face is close enough to the camera (e.g., box is at least 300px wide)
                        if (boundingBox.width() > 300) {
                            isAnalyzing = false // Lock scanner

                            // 1. Convert ImageProxy (YUV format) to a standard Bitmap
                            val bitmap = imageProxyToBitmap(imageProxy)

                            if (bitmap != null) {
                                // 2. Crop the Bitmap to just the face bounding box
                                // Add a 10% margin so we don't chop off the chin/hairline
                                val marginX = (boundingBox.width() * 0.1).toInt()
                                val marginY = (boundingBox.height() * 0.1).toInt()

                                val safeRect = Rect(
                                    maxOf(0, boundingBox.left - marginX),
                                    maxOf(0, boundingBox.top - marginY),
                                    minOf(bitmap.width, boundingBox.right + marginX),
                                    minOf(bitmap.height, boundingBox.bottom + marginY)
                                )

                                val croppedFace = Bitmap.createBitmap(
                                    bitmap,
                                    safeRect.left,
                                    safeRect.top,
                                    safeRect.width(),
                                    safeRect.height()
                                )

                                // 3. Compress to JPEG and encode to Base64
                                val base64String = bitmapToBase64(croppedFace)
                                onFaceExtracted(base64String)
                            } else {
                                isAnalyzing = true // Reset if conversion failed
                            }
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    // --- Helper Functions ---

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Compress to 80% quality to save bandwidth while retaining recognition detail
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
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

        // Android camera sensor data is often rotated 90 degrees natively.
        // We must rotate the bitmap based on the ImageProxy rotation metadata.
        val matrix = android.graphics.Matrix()
        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

        // If it's the front camera, we also need to mirror it horizontally
        matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)

        return Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    }
}