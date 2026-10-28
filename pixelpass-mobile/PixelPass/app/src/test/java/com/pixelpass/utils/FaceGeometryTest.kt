package com.pixelpass.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val FRAME_WIDTH = 640
private const val FRAME_HEIGHT = 480

/**
 * The capture button is only enabled while [FaceGeometry.status] says the face is inside the
 * oval, so these rules are the difference between a capture and an empty doorway.
 */
class FaceGeometryTest {

    private val centredFace = FaceBox(left = 240, top = 150, right = 420, bottom = 330)

    @Test
    fun centredLargeFaceIsAligned() {
        assertEquals(
            FaceDetectionStatus.ALIGNED,
            FaceGeometry.status(centredFace, FRAME_WIDTH, FRAME_HEIGHT)
        )
    }

    @Test
    fun faceWiderThanAQuarterOfTheFrameIsCloseEnough() {
        val exactlyAtTheLimit = FaceBox(left = 240, top = 150, right = 400, bottom = 310)
        assertEquals(
            FaceDetectionStatus.ALIGNED,
            FaceGeometry.status(exactlyAtTheLimit, FRAME_WIDTH, FRAME_HEIGHT)
        )

        val onePixelNarrower = FaceBox(left = 240, top = 150, right = 399, bottom = 310)
        assertEquals(
            FaceDetectionStatus.TOO_FAR,
            FaceGeometry.status(onePixelNarrower, FRAME_WIDTH, FRAME_HEIGHT)
        )
    }

    @Test
    fun faceOutsideTheOvalIsOffCentre() {
        val farLeft = FaceBox(left = 20, top = 150, right = 200, bottom = 330)
        val farRight = FaceBox(left = 440, top = 150, right = 620, bottom = 330)
        val tooLow = FaceBox(left = 240, top = 300, right = 420, bottom = 480)

        assertEquals(
            FaceDetectionStatus.OFF_CENTER,
            FaceGeometry.status(farLeft, FRAME_WIDTH, FRAME_HEIGHT)
        )
        assertEquals(
            FaceDetectionStatus.OFF_CENTER,
            FaceGeometry.status(farRight, FRAME_WIDTH, FRAME_HEIGHT)
        )
        assertEquals(
            FaceDetectionStatus.OFF_CENTER,
            FaceGeometry.status(tooLow, FRAME_WIDTH, FRAME_HEIGHT)
        )
    }

    @Test
    fun portraitBufferIsMeasuredAfterTheQuarterTurn() {
        // A phone held upright hands over a 640x480 landscape buffer rotated by 90 degrees.
        assertEquals(FrameSize(480, 640), FaceGeometry.uprightFrameSize(640, 480, 90))
        assertEquals(FrameSize(480, 640), FaceGeometry.uprightFrameSize(640, 480, 270))
        assertEquals(FrameSize(640, 480), FaceGeometry.uprightFrameSize(640, 480, 0))
        assertEquals(FrameSize(640, 480), FaceGeometry.uprightFrameSize(640, 480, 180))
    }

    @Test
    fun centredFaceIsAlignedOnAnUprightFrame() {
        // The same face measured against the rotated frame instead of the raw buffer.
        val frame = FaceGeometry.uprightFrameSize(FRAME_WIDTH, FRAME_HEIGHT, 90)

        assertEquals(
            FaceDetectionStatus.ALIGNED,
            FaceGeometry.status(centredFace, frame.width, frame.height)
        )
    }

    @Test
    fun cropKeepsAMarginAroundTheFace() {
        assertEquals(
            CropRect(left = 222, top = 132, right = 438, bottom = 348),
            FaceGeometry.cropRect(centredFace, FRAME_WIDTH, FRAME_HEIGHT, mirrored = false)
        )
    }

    @Test
    fun mirroredCropFollowsTheFlippedFrame() {
        // The front camera mirrors the frame, so the same face lands on the opposite side.
        assertEquals(
            CropRect(left = 202, top = 132, right = 418, bottom = 348),
            FaceGeometry.cropRect(centredFace, FRAME_WIDTH, FRAME_HEIGHT, mirrored = true)
        )
    }

    @Test
    fun cropStaysInsideTheBitmap() {
        val nearTheEdge = FaceBox(left = 10, top = 5, right = 150, bottom = 145)

        assertEquals(
            CropRect(left = 0, top = 0, right = 164, bottom = 159),
            FaceGeometry.cropRect(nearTheEdge, FRAME_WIDTH, FRAME_HEIGHT, mirrored = false)
        )
    }

    @Test
    fun degenerateBoxHasNoCrop() {
        val empty = FaceBox(left = 100, top = 100, right = 100, bottom = 100)

        assertNull(FaceGeometry.cropRect(empty, FRAME_WIDTH, FRAME_HEIGHT, mirrored = false))
    }
}
