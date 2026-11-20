package com.homealert.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private fun solidFrame(size: Int, luma: Int): ByteArray =
        ByteArray(size) { luma.toByte() }

    private fun frameWithPatch(
        totalSize: Int,
        baseLuma: Int,
        patchStart: Int,
        patchCount: Int,
        patchLuma: Int
    ): ByteArray {
        val arr = ByteArray(totalSize) { baseLuma.toByte() }
        for (i in patchStart until (patchStart + patchCount).coerceAtMost(totalSize)) {
            arr[i] = patchLuma.toByte()
        }
        return arr
    }

    @Test
    fun identicalFramesRemainIdle() {
        val detector = MotionDetector(minFrameIntervalMs = 250L)
        val config = MotionDetector.Config(sensitivityAreaPercent = 5f, pixelLumaDeltaThreshold = 25, cooldownSeconds = 10)
        val frame = solidFrame(1000, 80)

        val r1 = detector.analyzeFrame(frame, 1_000L, config)
        assertTrue(r1 is MotionDetector.AnalysisResult.BaselineCaptured)

        val r2 = detector.analyzeFrame(frame, 1_300L, config)
        assertTrue(r2 is MotionDetector.AnalysisResult.Idle)
        assertEquals(0f, (r2 as MotionDetector.AnalysisResult.Idle).changedAreaPercent, 0.01f)
    }

    @Test
    fun throttlesFramesFasterThanInterval() {
        val detector = MotionDetector(minFrameIntervalMs = 280L)
        val config = MotionDetector.Config()
        val frame = solidFrame(100, 50)

        detector.analyzeFrame(frame, 1_000L, config)
        val throttled = detector.analyzeFrame(frame, 1_100L, config)
        assertTrue(throttled is MotionDetector.AnalysisResult.Throttled)
    }

    @Test
    fun requiresTwoConsecutiveFramesDebounceBeforeTriggering() {
        val detector = MotionDetector(minFrameIntervalMs = 200L, requiredConsecutiveFrames = 2)
        val config = MotionDetector.Config(sensitivityAreaPercent = 5f, pixelLumaDeltaThreshold = 25, cooldownSeconds = 15)

        val bg = solidFrame(1000, 60)
        val motion1 = frameWithPatch(1000, 60, patchStart = 100, patchCount = 120, patchLuma = 180)
        val motion2 = frameWithPatch(1000, 60, patchStart = 220, patchCount = 120, patchLuma = 180)

        detector.analyzeFrame(bg, 1_000L, config)

        // Frame 1 with localized 12% motion should debounce (count = 1/2), not trigger yet
        val firstHit = detector.analyzeFrame(motion1, 1_300L, config)
        assertTrue(firstHit is MotionDetector.AnalysisResult.Idle)
        assertEquals(1, (firstHit as MotionDetector.AnalysisResult.Idle).consecutiveFrames)

        // Frame 2 with localized motion confirms trigger
        val secondHit = detector.analyzeFrame(motion2, 1_600L, config)
        assertTrue(secondHit is MotionDetector.AnalysisResult.Triggered)
    }

    @Test
    fun globalExposureJumpIsFilteredWithoutFalseAlarm() {
        val detector = MotionDetector(minFrameIntervalMs = 200L)
        val config = MotionDetector.Config(sensitivityAreaPercent = 5f, pixelLumaDeltaThreshold = 25, cooldownSeconds = 15)

        val darkFrame = solidFrame(1000, 60)
        // Entire frame jumps +45 luma uniformly (e.g., cloud cleared or camera auto-exposure stepped up)
        val brightFrame = solidFrame(1000, 105)

        detector.analyzeFrame(darkFrame, 1_000L, config)
        val res = detector.analyzeFrame(brightFrame, 1_300L, config)
        assertTrue(res is MotionDetector.AnalysisResult.GlobalExposureSuppressed)
    }

    @Test
    fun enforcesCooldownTimerAfterTrigger() {
        val detector = MotionDetector(minFrameIntervalMs = 200L, requiredConsecutiveFrames = 2)
        val config = MotionDetector.Config(sensitivityAreaPercent = 5f, pixelLumaDeltaThreshold = 25, cooldownSeconds = 20)

        val bg = solidFrame(1000, 50)
        val m1 = frameWithPatch(1000, 50, 0, 100, 170)
        val m2 = frameWithPatch(1000, 50, 100, 100, 170)
        val m3 = frameWithPatch(1000, 50, 200, 100, 170)
        val m4 = frameWithPatch(1000, 50, 300, 100, 170)

        detector.analyzeFrame(bg, 1_000L, config)
        detector.analyzeFrame(m1, 1_300L, config)
        val triggered = detector.analyzeFrame(m2, 1_600L, config)
        assertTrue(triggered is MotionDetector.AnalysisResult.Triggered)

        // 5 seconds later: still inside 20s cooldown
        detector.analyzeFrame(m3, 6_000L, config)
        val duringCooldown = detector.analyzeFrame(m4, 6_300L, config)
        assertTrue(duringCooldown is MotionDetector.AnalysisResult.Idle)
        assertTrue((duringCooldown as MotionDetector.AnalysisResult.Idle).cooldownRemainingSeconds > 0)

        // 21 seconds after trigger: cooldown cleared, can trigger again
        detector.analyzeFrame(m1, 22_000L, config)
        val afterCooldown = detector.analyzeFrame(m2, 22_300L, config)
        assertTrue(afterCooldown is MotionDetector.AnalysisResult.Triggered)
    }

    private fun frameWith2DBox(
        width: Int,
        height: Int,
        baseLuma: Int,
        xRange: IntRange,
        yRange: IntRange,
        patchLuma: Int
    ): ByteArray {
        val arr = ByteArray(width * height) { baseLuma.toByte() }
        for (y in yRange) {
            for (x in xRange) {
                if (x in 0 until width && y in 0 until height) {
                    arr[y * width + x] = patchLuma.toByte()
                }
            }
        }
        return arr
    }

    @Test
    fun sixZoneDualLineHysteresisIgnoresGuardbandJitterAndTriggersOnFullCrossing() {
        val detector = MotionDetector(minFrameIntervalMs = 200L, requiredConsecutiveFrames = 2)
        val config = MotionDetector.Config(
            sensitivityAreaPercent = 4f,
            pixelLumaDeltaThreshold = 25,
            cooldownSeconds = 10,
            requireRegionCrossing = true,
            zoneLayout = com.homealert.android.data.model.ZoneLayout.SIX_ZONES
        )

        val w = 30
        val h = 20
        val bg = solidFrame(w * h, 50)
        // Top-Left zone (x in 1..6, y in 1..10 -> cx ~ 0.12, cy ~ 0.27)
        val topLeftA = frameWith2DBox(w, h, 50, 1..6, 1..10, 170)
        val topLeftB = frameWith2DBox(w, h, 50, 3..8, 1..10, 170)
        // Two consecutive frames shifting inside the Left|Center guardband (cx = 10.5/30 = 0.35 in 0.303..0.363)
        val guardbandA = frameWith2DBox(w, h, 50, 8..12, 0..12, 170)
        val guardbandB = frameWith2DBox(w, h, 50, 9..13, 0..12, 170)
        // Cleanly past second parallel border line into Top-Center (cx ~ 0.44, cy ~ 0.27)
        val topCenter = frameWith2DBox(w, h, 50, 14..19, 1..8, 170)

        detector.analyzeFrame(bg, 1_000L, config, w, h)
        val r1 = detector.analyzeFrame(topLeftA, 1_300L, config, w, h)
        assertTrue(r1 is MotionDetector.AnalysisResult.Idle)
        assertEquals("Top-Left", (r1 as MotionDetector.AnalysisResult.Idle).boundingBox?.zoneLabel)

        // Still inside Top-Left -> does not trigger
        val r2 = detector.analyzeFrame(topLeftB, 1_600L, config, w, h)
        assertTrue(r2 is MotionDetector.AnalysisResult.Idle)

        // Enters dual-line guardband only -> suspect, must NOT trigger yet
        val rBandEnter = detector.analyzeFrame(guardbandA, 1_900L, config, w, h)
        assertTrue(rBandEnter is MotionDetector.AnalysisResult.Idle)
        val rBandHover = detector.analyzeFrame(guardbandB, 2_200L, config, w, h)
        assertTrue(rBandHover is MotionDetector.AnalysisResult.Idle)
        assertTrue((rBandHover as MotionDetector.AnalysisResult.Idle).boundingBox?.inGuardband == true)

        // Exits past second parallel line into Top-Center -> confirmed crossing triggers!
        val r3 = detector.analyzeFrame(topCenter, 2_500L, config, w, h)
        assertTrue(r3 is MotionDetector.AnalysisResult.Triggered)
        val triggeredBox = (r3 as MotionDetector.AnalysisResult.Triggered).boundingBox
        assertEquals("Top-Center", triggeredBox?.zoneLabel)
        assertEquals("Top-Left", triggeredBox?.crossedFromLabel)
    }

    @Test
    fun fourZoneSingleBorderCrossingTriggersAfterPauseAndPreviewModeNeverTriggers() {
        val detector = MotionDetector(minFrameIntervalMs = 200L, requiredConsecutiveFrames = 2)
        val config = MotionDetector.Config(
            sensitivityAreaPercent = 4f,
            pixelLumaDeltaThreshold = 25,
            cooldownSeconds = 10,
            requireRegionCrossing = true,
            zoneLayout = com.homealert.android.data.model.ZoneLayout.FOUR_ZONES
        )

        val w = 30
        val h = 20
        val bg = solidFrame(w * h, 50)
        // Top-Left (24px -> cy = 0.175); Bottom-Left (48px -> combined diff cy = 0.575 > 0.53 guardband)
        val topLeft = frameWith2DBox(w, h, 50, 2..7, 2..5, 170)
        val topLeftShifted = frameWith2DBox(w, h, 50, 2..7, 3..6, 170)
        val bottomLeft = frameWith2DBox(w, h, 50, 2..9, 13..18, 170)

        // Setup previewOnly = true emits bounding box without triggering alert
        detector.analyzeFrame(bg, 1_000L, config, w, h, previewOnly = true)
        detector.analyzeFrame(topLeft, 1_300L, config, w, h, previewOnly = true)
        val previewCross = detector.analyzeFrame(bottomLeft, 1_600L, config, w, h, previewOnly = true)
        assertTrue(previewCross is MotionDetector.AnalysisResult.Idle)
        assertEquals("Bottom-Left", (previewCross as MotionDetector.AnalysisResult.Idle).boundingBox?.zoneLabel)
        assertEquals("Top-Left", previewCross.boundingBox?.crossedFromLabel)

        // Now armed (previewOnly = false): crosses horizontal dual-line border Top-Left -> Bottom-Left after pause
        detector.reset()
        detector.analyzeFrame(bg, 2_000L, config, w, h)
        detector.analyzeFrame(topLeft, 2_300L, config, w, h)
        detector.analyzeFrame(topLeftShifted, 3_300L, config, w, h) // brief 1s pause
        val rCross = detector.analyzeFrame(bottomLeft, 3_700L, config, w, h)
        assertTrue(rCross is MotionDetector.AnalysisResult.Triggered)
        assertEquals("Bottom-Left", (rCross as MotionDetector.AnalysisResult.Triggered).boundingBox?.zoneLabel)
        assertEquals("Top-Left", rCross.boundingBox?.crossedFromLabel)
    }
}
