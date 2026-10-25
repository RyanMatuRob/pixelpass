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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream

enum class FaceDetectionStatus {
    NO_FACE,
    OFF_CENTER,
    TOO_FAR,
    ALIGNED
}

class FaceAnalyzer(
    private val onStatusUpdate: (FaceDetectionStatus) -> Unit,
    private val onFaceExtracted: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()

    private val detector = FaceDetection.getClient(options)
    private var isAnalyzing = true
    private var consecutiveAlignedFrames = 0

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isAnalyzing) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (!isAnalyzing) return@addOnSuccessListener

                    if (faces.size == 1) {
                        val face = faces.first()
                        val boundingBox = face.boundingBox

                        val frameWidth = imageProxy.width
                        val frameHeight = imageProxy.height

                        val faceWidth = boundingBox.width()
                        val centerX = boundingBox.centerX()
                        val centerY = boundingBox.centerY()

                        // Minimum face width requirement relative to frame
                        val minWidth = (frameWidth * 0.25f).toInt()

                        // Check if face is centered in the frame
                        val isCenteredX = centerX in (frameWidth * 0.25f).toInt()..(frameWidth * 0.75f).toInt()
                        val isCenteredY = centerY in (frameHeight * 0.20f).toInt()..(frameHeight * 0.80f).toInt()

                        if (faceWidth < minWidth) {
                            onStatusUpdate(FaceDetectionStatus.TOO_FAR)
                            consecutiveAlignedFrames = 0
                        } else if (!isCenteredX || !isCenteredY) {
                            onStatusUpdate(FaceDetectionStatus.OFF_CENTER)
                            consecutiveAlignedFrames = 0
                        } else {
                            consecutiveAlignedFrames++
                            onStatusUpdate(FaceDetectionStatus.ALIGNED)

                            // Require 3 consecutive stable frames within bounds before capturing
                            if (consecutiveAlignedFrames >= 3) {
                                isAnalyzing = false // Lock scanner
                                val bitmap = imageProxyToBitmap(imageProxy)

                                if (bitmap != null) {
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

                                    val base64String = bitmapToBase64(croppedFace)
                                    onFaceExtracted(base64String)
                                } else {
                                    isAnalyzing = true
                                    consecutiveAlignedFrames = 0
                                }
                            }
                        }
                    } else {
                        onStatusUpdate(FaceDetectionStatus.NO_FACE)
                        consecutiveAlignedFrames = 0
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
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

        val matrix = Matrix()
        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)

        return Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    }
}
