package com.pixelpass.utils

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.pixelpass.scan.ScanDebouncer

/**
 * Feeds QR codes from the camera to the scanner screen.
 *
 * ML Kit reports the same barcode on every analysed frame, so [debounce] drops
 * repeats of the code that is already being handled while still letting a
 * rotated pass through immediately.
 */
class QrCodeAnalyzer(
    private val debounce: ScanDebouncer = ScanDebouncer(),
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    // Configure ML Kit to ONLY look for QR codes (improves performance and battery life)
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val payload = barcode.rawValue?.takeIf { it.isNotBlank() } ?: continue
                    if (debounce.tryAcquire(payload)) {
                        onQrCodeScanned(payload)
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
    }

    /** Call this to allow the pass that was just handled to be scanned again. */
    fun resetScanner() = debounce.reset()
}
