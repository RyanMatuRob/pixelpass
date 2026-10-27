package com.pixelpass.utils

/**
 * A face rectangle in frame coordinates.
 *
 * Deliberately free of `android.graphics.Rect` so the alignment rules and the mirrored crop
 * can be unit tested on the JVM.
 */
data class FaceBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** Size of the analysed frame once the sensor rotation has been applied. */
data class FrameSize(
    val width: Int,
    val height: Int
)

/** A crop rectangle in bitmap coordinates. */
data class CropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Geometry of the capture oval, shared by the analyser and its tests.
 *
 * The oval is approximated on the analysed frame as a centred window: the face has to be
 * large enough to recognise and its centre has to sit inside that window. The guard's
 * capture button is gated on [FaceDetectionStatus.ALIGNED], so these ratios decide what
 * "a face inside the oval" means.
 */
object FaceGeometry {

    const val MIN_FACE_WIDTH_RATIO = 0.25f
    const val OVAL_LEFT_RATIO = 0.25f
    const val OVAL_RIGHT_RATIO = 0.75f
    const val OVAL_TOP_RATIO = 0.20f
    const val OVAL_BOTTOM_RATIO = 0.80f

    // Keep a little breathing room around the face: DeepFace sees a fuller head that way.
    const val CROP_MARGIN_RATIO = 0.1

    /**
     * The analysed frame the way ML Kit sees it.
     *
     * A face box is reported on the upright picture, not on the raw sensor buffer, so a
     * quarter-turn rotation swaps the buffer's width and height. Measuring the oval against the
     * unrotated buffer would demand a right-of-centre, above-centre face on a portrait phone.
     */
    fun uprightFrameSize(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): FrameSize =
        when (rotationDegrees) {
            90, 270 -> FrameSize(bufferHeight, bufferWidth)
            else -> FrameSize(bufferWidth, bufferHeight)
        }

    /** Where the face sits relative to the oval for one analysed frame. */
    fun status(box: FaceBox, frameWidth: Int, frameHeight: Int): FaceDetectionStatus {
        val minWidth = (frameWidth * MIN_FACE_WIDTH_RATIO).toInt()
        if (box.width < minWidth) return FaceDetectionStatus.TOO_FAR

        val insideOvalX =
            box.centerX in (frameWidth * OVAL_LEFT_RATIO).toInt()..(frameWidth * OVAL_RIGHT_RATIO).toInt()
        val insideOvalY =
            box.centerY in (frameHeight * OVAL_TOP_RATIO).toInt()..(frameHeight * OVAL_BOTTOM_RATIO).toInt()

        return if (insideOvalX && insideOvalY) FaceDetectionStatus.ALIGNED else FaceDetectionStatus.OFF_CENTER
    }

    /**
     * The region of the captured bitmap that holds the face, margins included.
     *
     * A mirrored frame (front camera) is flipped horizontally before it reaches this point,
     * so the box has to be flipped with it or the wrong half of the picture is sent for
     * comparison.
     *
     * @return `null` when the box cannot produce a usable crop.
     */
    fun cropRect(box: FaceBox, bitmapWidth: Int, bitmapHeight: Int, mirrored: Boolean): CropRect? {
        val left = if (mirrored) bitmapWidth - box.right else box.left
        val right = if (mirrored) bitmapWidth - box.left else box.right

        val marginX = ((right - left) * CROP_MARGIN_RATIO).toInt()
        val marginY = (box.height * CROP_MARGIN_RATIO).toInt()

        val crop = CropRect(
            (left - marginX).coerceAtLeast(0),
            (box.top - marginY).coerceAtLeast(0),
            (right + marginX).coerceAtMost(bitmapWidth),
            (box.bottom + marginY).coerceAtMost(bitmapHeight)
        )

        return if (crop.width <= 0 || crop.height <= 0) null else crop
    }
}
