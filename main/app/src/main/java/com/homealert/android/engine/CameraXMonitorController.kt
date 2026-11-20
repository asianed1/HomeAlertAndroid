package com.homealert.android.engine

import android.content.Context
import android.media.MediaActionSound
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.MotionTelemetry
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraXMonitorController(
    private val context: Context,
    private val onTelemetry: (MotionTelemetry) -> Unit,
    private val onMotionTriggered: (jpegBytes: ByteArray, motionPercent: Float) -> Unit,
    private val onError: (String) -> Unit
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val motionDetector = MotionDetector()
    private val captureInFlight = AtomicBoolean(false)
    private val shutterSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
    }

    @Volatile
    private var isArmed: Boolean = false

    @Volatile
    private var currentSettings: AppSettings = AppSettings()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lastFrameTickMs: Long = 0L

    fun updateSettings(settings: AppSettings) {
        currentSettings = settings
    }

    fun setArmed(armed: Boolean) {
        isArmed = armed
        motionDetector.reset()
        if (!armed) {
            onTelemetry(MotionTelemetry())
        }
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        stealthMode: Boolean
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val analysisResolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            processFrame(imageProxy)
                        }
                    }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(
                        if (currentSettings.deterrentFlashEnabled) {
                            ImageCapture.FLASH_MODE_ON
                        } else {
                            ImageCapture.FLASH_MODE_OFF
                        }
                    )
                    .setJpegQuality(92)
                    .build()
                imageCapture = capture

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (previewView != null && !stealthMode) {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis, capture)
                } else {
                    // In Stealth / Blackout Mode, keep ImageAnalysis & ImageCapture alive without Preview GPU overhead
                    provider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis, capture)
                }
            } catch (exc: Exception) {
                onError("CameraX bind error: ${exc.message ?: exc.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val settings = currentSettings
            val lumaFrame = MotionDetector.extractSubsampledLuma(imageProxy, sampleStep = 4)
            val result = motionDetector.analyzeFrame(
                lumaSamples = lumaFrame.bytes,
                timestampMs = now,
                config = MotionDetector.Config(
                    sensitivityAreaPercent = settings.sensitivityPercent,
                    pixelLumaDeltaThreshold = settings.lumaDeltaThreshold,
                    cooldownSeconds = settings.cooldownSeconds,
                    requireRegionCrossing = settings.requireRegionCrossing,
                    zoneLayout = settings.zoneLayout
                ),
                width = lumaFrame.width,
                height = lumaFrame.height,
                previewOnly = !isArmed
            )

            when (result) {
                is MotionDetector.AnalysisResult.Throttled -> Unit
                is MotionDetector.AnalysisResult.BaselineCaptured -> {
                    lastFrameTickMs = now
                }
                is MotionDetector.AnalysisResult.GlobalExposureSuppressed -> {
                    val fps = computeFps(now)
                    onTelemetry(
                        MotionTelemetry(
                            changedAreaPercent = 0f,
                            meanLumaShift = result.meanSignedDelta,
                            globalExposureSuppressed = true,
                            consecutiveMotionFrames = 0,
                            cooldownRemainingSeconds = 0,
                            lastAnalysisFps = fps,
                            boundingBox = null
                        )
                    )
                }
                is MotionDetector.AnalysisResult.Idle -> {
                    val fps = computeFps(now)
                    onTelemetry(
                        MotionTelemetry(
                            changedAreaPercent = result.changedAreaPercent,
                            meanLumaShift = 0f,
                            globalExposureSuppressed = false,
                            consecutiveMotionFrames = result.consecutiveFrames,
                            cooldownRemainingSeconds = result.cooldownRemainingSeconds,
                            lastAnalysisFps = fps,
                            boundingBox = result.boundingBox
                        )
                    )
                }
                is MotionDetector.AnalysisResult.Triggered -> {
                    val fps = computeFps(now)
                    onTelemetry(
                        MotionTelemetry(
                            changedAreaPercent = result.changedAreaPercent,
                            meanLumaShift = 0f,
                            globalExposureSuppressed = false,
                            consecutiveMotionFrames = 2,
                            cooldownRemainingSeconds = settings.cooldownSeconds,
                            lastAnalysisFps = fps,
                            boundingBox = result.boundingBox
                        )
                    )
                    captureFullResolutionSnapshot(result.changedAreaPercent)
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun computeFps(nowMs: Long): Float {
        val prev = lastFrameTickMs
        lastFrameTickMs = nowMs
        if (prev == 0L || nowMs <= prev) return 3.5f
        return (1000f / (nowMs - prev).toFloat()).coerceIn(0.5f, 10f)
    }

    private fun captureFullResolutionSnapshot(motionPercent: Float) {
        val capture = imageCapture ?: return
        if (!captureInFlight.compareAndSet(false, true)) return

        val settings = currentSettings
        capture.flashMode = if (settings.deterrentFlashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }

        if (settings.deterrentShutterSoundEnabled) {
            runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        }

        val outputStream = ByteArrayOutputStream()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream).build()

        capture.takePicture(
            outputOptions,
            analysisExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        val bytes = outputStream.toByteArray()
                        if (bytes.isNotEmpty()) {
                            onMotionTriggered(bytes, motionPercent)
                        }
                    } finally {
                        // Rebase reference frame after flash illumination settles
                        motionDetector.reset()
                        captureInFlight.set(false)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    motionDetector.reset()
                    captureInFlight.set(false)
                    onError("Snapshot capture failed: ${exception.message}")
                }
            }
        )
    }

    fun shutdown() {
        isArmed = false
        cameraProvider?.unbindAll()
        runCatching { shutterSound.release() }
        analysisExecutor.shutdownNow()
    }
}
