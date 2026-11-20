package com.homealert.android.engine

import androidx.camera.core.ImageProxy
import com.homealert.android.data.model.MotionBoundingBox
import com.homealert.android.data.model.ZoneLayout
import kotlin.math.abs

/**
 * Pure Y-plane luma motion detection engine with:
 * - Frame throttling (~3-4 FPS, default 280ms interval)
 * - Global whole-frame exposure jump filtering (clouds, auto-exposure shifts)
 * - 2-frame consecutive motion debounce
 * - Selectable 4-Zone (2x2) or 6-Zone (3x2) spatial bounding box with dual-line hysteresis guardbands
 * - Setup/calibration preview mode (live bounding box without firing triggers)
 * - Configurable cooldown timer between verified triggers
 */
class MotionDetector(
    private val minFrameIntervalMs: Long = 280L, // ~3.5 FPS
    private val requiredConsecutiveFrames: Int = 2,
    private val globalShiftDominanceRatio: Float = 0.65f,
    private val globalShiftMinMeanDelta: Float = 10.0f,
    private val halfGuardband: Float = 0.03f // ~6% wide dual-line border guardband
) {
    data class Config(
        val sensitivityAreaPercent: Float = 5.0f,
        val pixelLumaDeltaThreshold: Int = 26,
        val cooldownSeconds: Int = 20,
        val requireRegionCrossing: Boolean = false,
        val zoneLayout: ZoneLayout = ZoneLayout.FOUR_ZONES
    )

    data class SubsampledLumaFrame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    )

    sealed class AnalysisResult {
        data object Throttled : AnalysisResult()
        data object BaselineCaptured : AnalysisResult()
        data class GlobalExposureSuppressed(
            val meanSignedDelta: Float,
            val dominantDirectionRatio: Float
        ) : AnalysisResult()

        data class Idle(
            val changedAreaPercent: Float,
            val consecutiveFrames: Int,
            val cooldownRemainingSeconds: Int,
            val boundingBox: MotionBoundingBox? = null
        ) : AnalysisResult()

        data class Triggered(
            val changedAreaPercent: Float,
            val timestampMs: Long,
            val boundingBox: MotionBoundingBox? = null
        ) : AnalysisResult()
    }

    private var previousLuma: ByteArray? = null
    private var lastAnalyzedTimestampMs: Long = 0L
    private var lastTriggerTimestampMs: Long = Long.MIN_VALUE / 2
    private var consecutiveMotionCount: Int = 0
    private var activeSequenceOriginLabel: String? = null
    private var lastOriginZoneSeenMs: Long = 0L

    private val originMemoryWindowMs: Long = 4_000L

    fun reset() {
        previousLuma = null
        lastAnalyzedTimestampMs = 0L
        consecutiveMotionCount = 0
        activeSequenceOriginLabel = null
        lastOriginZoneSeenMs = 0L
    }

    fun analyzeFrame(
        lumaSamples: ByteArray,
        timestampMs: Long,
        config: Config,
        width: Int = lumaSamples.size,
        height: Int = 1,
        previewOnly: Boolean = false
    ): AnalysisResult {
        if (lastAnalyzedTimestampMs != 0L && (timestampMs - lastAnalyzedTimestampMs) < minFrameIntervalMs) {
            return AnalysisResult.Throttled
        }
        lastAnalyzedTimestampMs = timestampMs

        val prev = previousLuma
        if (prev == null || prev.size != lumaSamples.size) {
            previousLuma = lumaSamples.copyOf()
            consecutiveMotionCount = 0
            activeSequenceOriginLabel = null
            lastOriginZoneSeenMs = 0L
            return AnalysisResult.BaselineCaptured
        }

        val totalPixels = lumaSamples.size
        if (totalPixels == 0 || width <= 0 || height <= 0) {
            return AnalysisResult.Idle(0f, 0, 0, null)
        }

        var changedPixels = 0
        var positiveShifts = 0
        var negativeShifts = 0
        var signedDeltaSum = 0L

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var sumX = 0L
        var sumY = 0L

        val deltaThreshold = config.pixelLumaDeltaThreshold
        for (i in 0 until totalPixels) {
            val cur = lumaSamples[i].toInt() and 0xFF
            val old = prev[i].toInt() and 0xFF
            val signedDelta = cur - old
            val absDelta = abs(signedDelta)

            signedDeltaSum += signedDelta
            if (signedDelta > 6) positiveShifts++
            else if (signedDelta < -6) negativeShifts++

            if (absDelta >= deltaThreshold) {
                changedPixels++
                val x = i % width
                val y = i / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sumX += x
                sumY += y
            }
        }

        previousLuma = lumaSamples.copyOf()

        val changedPercent = (changedPixels.toFloat() / totalPixels.toFloat()) * 100f
        val meanSignedDelta = signedDeltaSum.toFloat() / totalPixels.toFloat()
        val dominantRatio = maxOf(positiveShifts, negativeShifts).toFloat() / totalPixels.toFloat()

        // Filter sudden whole-frame auto-exposure / cloud-passing illumination shifts
        val isGlobalExposureJump =
            dominantRatio >= globalShiftDominanceRatio &&
                abs(meanSignedDelta) >= globalShiftMinMeanDelta

        if (isGlobalExposureJump) {
            consecutiveMotionCount = 0
            activeSequenceOriginLabel = null
            lastOriginZoneSeenMs = 0L
            return AnalysisResult.GlobalExposureSuppressed(
                meanSignedDelta = meanSignedDelta,
                dominantDirectionRatio = dominantRatio
            )
        }

        if (activeSequenceOriginLabel != null &&
            (timestampMs - lastOriginZoneSeenMs) > originMemoryWindowMs
        ) {
            activeSequenceOriginLabel = null
            lastOriginZoneSeenMs = 0L
        }

        val boundingBox = if (changedPixels > 0 && maxX >= minX && maxY >= minY) {
            val cx = ((sumX.toFloat() / changedPixels.toFloat()) / width.toFloat().coerceAtLeast(1f))
                .coerceIn(0f, 1f)
            val cy = ((sumY.toFloat() / changedPixels.toFloat()) / height.toFloat().coerceAtLeast(1f))
                .coerceIn(0f, 1f)

            val resolvedZone = resolveZoneWithHysteresis(cx, cy, config.zoneLayout, halfGuardband)
            val inGuardband = resolvedZone == null
            val origin = activeSequenceOriginLabel
            val crossedFrom = if (resolvedZone != null && origin != null && origin != resolvedZone) {
                origin
            } else {
                null
            }

            MotionBoundingBox(
                left = (minX.toFloat() / width.toFloat()).coerceIn(0f, 1f),
                top = (minY.toFloat() / height.toFloat()).coerceIn(0f, 1f),
                right = ((maxX + 1).toFloat() / width.toFloat()).coerceIn(0f, 1f),
                bottom = ((maxY + 1).toFloat() / height.toFloat()).coerceIn(0f, 1f),
                centroidX = cx,
                centroidY = cy,
                zoneLabel = resolvedZone ?: "Border Band",
                crossedFromLabel = crossedFrom,
                inGuardband = inGuardband
            )
        } else {
            null
        }

        val cooldownMs = config.cooldownSeconds * 1000L
        val elapsedSinceTrigger = timestampMs - lastTriggerTimestampMs
        val cooldownRemaining = if (elapsedSinceTrigger < cooldownMs) {
            ((cooldownMs - elapsedSinceTrigger + 999L) / 1000L).toInt().coerceAtLeast(1)
        } else {
            0
        }

        if (cooldownRemaining > 0) {
            consecutiveMotionCount = 0
            activeSequenceOriginLabel = null
            lastOriginZoneSeenMs = 0L
        } else if (changedPercent >= config.sensitivityAreaPercent && boundingBox != null) {
            val hadPriorZoneInWindow = activeSequenceOriginLabel != null
            if (!boundingBox.inGuardband) {
                if (!hadPriorZoneInWindow) {
                    activeSequenceOriginLabel = boundingBox.zoneLabel
                }
                lastOriginZoneSeenMs = timestampMs
            } else if (hadPriorZoneInWindow) {
                // Keep origin alive while passing through a dual-line guardband
                lastOriginZoneSeenMs = timestampMs
            }

            consecutiveMotionCount++

            val crossedDualBorder = boundingBox.crossedFromLabel != null
            val readyToTrigger = if (config.requireRegionCrossing) {
                crossedDualBorder && (consecutiveMotionCount >= requiredConsecutiveFrames || hadPriorZoneInWindow)
            } else {
                consecutiveMotionCount >= requiredConsecutiveFrames
            }

            if (!previewOnly && readyToTrigger) {
                lastTriggerTimestampMs = timestampMs
                consecutiveMotionCount = 0
                activeSequenceOriginLabel = null
                lastOriginZoneSeenMs = 0L
                return AnalysisResult.Triggered(
                    changedAreaPercent = changedPercent,
                    timestampMs = timestampMs,
                    boundingBox = boundingBox
                )
            }
        } else {
            consecutiveMotionCount = 0
        }

        return AnalysisResult.Idle(
            changedAreaPercent = changedPercent,
            consecutiveFrames = consecutiveMotionCount,
            cooldownRemainingSeconds = cooldownRemaining,
            boundingBox = boundingBox
        )
    }

    companion object {
        /**
         * Resolves a centroid (cx, cy in 0..1) into a 4-Zone (2x2) or 6-Zone (3x2) label,
         * returning null when the centroid lies inside any dual-line border guardband.
         */
        fun resolveZoneWithHysteresis(
            cx: Float,
            cy: Float,
            layout: ZoneLayout,
            halfBand: Float = 0.03f
        ): String? {
            for (k in 1 until layout.cols) {
                val border = k.toFloat() / layout.cols.toFloat()
                if (cx >= (border - halfBand) && cx <= (border + halfBand)) {
                    return null
                }
            }
            for (r in 1 until layout.rows) {
                val border = r.toFloat() / layout.rows.toFloat()
                if (cy >= (border - halfBand) && cy <= (border + halfBand)) {
                    return null
                }
            }

            val colIdx = (cx * layout.cols).toInt().coerceIn(0, layout.cols - 1)
            val rowIdx = (cy * layout.rows).toInt().coerceIn(0, layout.rows - 1)
            val rowName = if (rowIdx == 0) "Top" else "Bottom"
            val colName = when (layout) {
                ZoneLayout.FOUR_ZONES -> if (colIdx == 0) "Left" else "Right"
                ZoneLayout.SIX_ZONES -> when (colIdx) {
                    0 -> "Left"
                    1 -> "Center"
                    else -> "Right"
                }
            }
            return "$rowName-$colName"
        }

        fun extractSubsampledLuma(image: ImageProxy, sampleStep: Int = 4): SubsampledLumaFrame {
            val yPlane = image.planes[0]
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val width = image.width
            val height = image.height

            val sampledCols = (width + sampleStep - 1) / sampleStep
            val sampledRows = (height + sampleStep - 1) / sampleStep
            val output = ByteArray(sampledCols * sampledRows)

            buffer.rewind()
            var outIdx = 0
            for (y in 0 until height step sampleStep) {
                val rowBase = y * rowStride
                for (x in 0 until width step sampleStep) {
                    val index = rowBase + x * pixelStride
                    if (index < buffer.limit() && outIdx < output.size) {
                        output[outIdx++] = buffer.get(index)
                    }
                }
            }
            return SubsampledLumaFrame(output, sampledCols, sampledRows)
        }
    }
}
