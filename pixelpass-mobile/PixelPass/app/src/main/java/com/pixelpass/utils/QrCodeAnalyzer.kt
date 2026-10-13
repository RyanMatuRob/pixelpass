package com.pixelpass.utils

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    // Configure ML Kit to ONLY look for QR codes (improves performance and battery life)
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    // Flag to prevent the scanner from triggering the callback 60 times a second
    private var isScanning = true

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isScanning) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { jwt ->
                            isScanning = false // Lock the scanner once we get a hit
                            onQrCodeScanned(jwt)
                            break
                        }
                    }
                }
                .addOnFailureListener {
                    // Ignore transient frame failures
                }
                .addOnCompleteListener {
                    imageProxy.close() // MUST close the proxy to receive the next frame
                }
        } else {
            imageProxy.close()
        }
    }

    // Call this to resume scanning after processing a visitor
    fun resetScanner() {
        isScanning = true
    }
}