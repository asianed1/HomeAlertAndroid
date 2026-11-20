package com.homealert.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.homealert.android.HomeAlertApplication
import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.DiagnosticTestResult
import com.homealert.android.data.model.MonitoringState
import com.homealert.android.data.model.MotionTelemetry
import com.homealert.android.data.model.NetworkHealthState
import com.homealert.android.data.model.SimSubscriptionInfo
import com.homealert.android.data.model.SnapshotEntry
import com.homealert.android.engine.CameraXMonitorController
import com.homealert.android.service.MonitoringForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class HomeAlertViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HomeAlertApplication
    private val settingsRepo = app.settingsRepository
    private val mediaRepo = app.mediaStoreRepository
    private val dispatcher = app.alertDispatcher

    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val _monitoringState = MutableStateFlow(MonitoringState.IDLE)
    val monitoringState: StateFlow<MonitoringState> = _monitoringState.asStateFlow()

    private val _exitDelayRemaining = MutableStateFlow(0)
    val exitDelayRemaining: StateFlow<Int> = _exitDelayRemaining.asStateFlow()

    private val _stealthActive = MutableStateFlow(false)
    val stealthActive: StateFlow<Boolean> = _stealthActive.asStateFlow()

    private val _telemetry = MutableStateFlow(MotionTelemetry())
    val telemetry: StateFlow<MotionTelemetry> = _telemetry.asStateFlow()

    private val _networkHealth = MutableStateFlow(NetworkHealthState())
    val networkHealth: StateFlow<NetworkHealthState> = _networkHealth.asStateFlow()

    private val _snapshots = MutableStateFlow<List<SnapshotEntry>>(emptyList())
    val snapshots: StateFlow<List<SnapshotEntry>> = _snapshots.asStateFlow()

    private val _availableSims = MutableStateFlow<List<SimSubscriptionInfo>>(emptyList())
    val availableSims: StateFlow<List<SimSubscriptionInfo>> = _availableSims.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<DiagnosticTestResult>>(emptyList())
    val diagnosticLogs: StateFlow<List<DiagnosticTestResult>> = _diagnosticLogs.asStateFlow()

    private val _diagnosticRunning = MutableStateFlow(false)
    val diagnosticRunning: StateFlow<Boolean> = _diagnosticRunning.asStateFlow()

    private val _lastBannerMessage = MutableStateFlow<String?>(null)
    val lastBannerMessage: StateFlow<String?> = _lastBannerMessage.asStateFlow()

    private var countdownJob: Job? = null

    private val cameraController = CameraXMonitorController(
        context = app,
        onTelemetry = { _telemetry.value = it },
        onMotionTriggered = { jpegBytes, motionPercent ->
            handleVerifiedMotion(jpegBytes, motionPercent)
        },
        onError = { _lastBannerMessage.value = it }
    )

    init {
        viewModelScope.launch {
            settings.collect { current ->
                cameraController.updateSettings(current)
                refreshNetworkHealth()
            }
        }
        refreshGallery()
        refreshSims()
        startNetworkHealthPolling()
    }

    private fun startNetworkHealthPolling() {
        viewModelScope.launch {
            while (true) {
                refreshNetworkHealth()
                delay(4_000L)
            }
        }
    }

    fun refreshNetworkHealth() {
        _networkHealth.value = dispatcher.inspectHealth(settings.value)
    }

    fun refreshSims() {
        _availableSims.value = dispatcher.getAvailableSims()
    }

    fun refreshGallery() {
        viewModelScope.launch {
            _snapshots.value = mediaRepo.loadGallerySnapshots()
        }
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        stealthMode: Boolean
    ) {
        cameraController.bindCamera(lifecycleOwner, previewView, stealthMode)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepo.updateSettings(transform)
    }

    fun toggleStealthMode(enabled: Boolean) {
        _stealthActive.value = enabled
    }

    fun toggleMonitoring() {
        when (_monitoringState.value) {
            MonitoringState.IDLE -> startMonitoring()
            MonitoringState.EXIT_DELAY, MonitoringState.ARMED -> stopMonitoring()
        }
    }

    private fun startMonitoring() {
        val current = settings.value
        MonitoringForegroundService.start(app, current.cameraLabel)

        if (current.exitDelayEnabled && current.exitDelaySeconds > 0) {
            _monitoringState.value = MonitoringState.EXIT_DELAY
            _exitDelayRemaining.value = current.exitDelaySeconds
            countdownJob?.cancel()
            countdownJob = viewModelScope.launch {
                for (sec in current.exitDelaySeconds downTo 1) {
                    _exitDelayRemaining.value = sec
                    delay(1_000L)
                }
                _exitDelayRemaining.value = 0
                armNow()
            }
        } else {
            armNow()
        }
    }

    private fun armNow() {
        _monitoringState.value = MonitoringState.ARMED
        cameraController.setArmed(true)
        if (settings.value.stealthAutoActivate) {
            _stealthActive.value = true
        }
    }

    fun stopMonitoring() {
        countdownJob?.cancel()
        countdownJob = null
        _exitDelayRemaining.value = 0
        _monitoringState.value = MonitoringState.IDLE
        _stealthActive.value = false
        cameraController.setArmed(false)
        MonitoringForegroundService.stop(app)
    }

    private fun handleVerifiedMotion(jpegBytes: ByteArray, motionPercent: Float) {
        viewModelScope.launch {
            val currentSettings = settings.value
            val saved = runCatching {
                mediaRepo.saveSnapshotAndCreateAlertCopy(
                    fullResJpegBytes = jpegBytes,
                    cameraLabel = currentSettings.cameraLabel,
                    motionPercent = motionPercent
                )
            }.getOrElse { err ->
                _lastBannerMessage.value = "Storage error: ${err.message}"
                return@launch
            }

            refreshGallery()

            val outcome = dispatcher.dispatchSnapshotAlert(
                fileName = saved.fileName,
                compressedFile = saved.compressedAlertFile,
                cameraLabel = currentSettings.cameraLabel,
                motionPercent = motionPercent,
                timestampMillis = saved.timestampMillis,
                settings = currentSettings
            )

            _lastBannerMessage.value = "${outcome.statusBadge} (${currentSettings.cameraLabel})"
            refreshGallery()
        }
    }

    fun deleteSnapshot(entry: SnapshotEntry) {
        viewModelScope.launch {
            if (mediaRepo.deleteSnapshot(entry)) {
                refreshGallery()
            }
        }
    }

    fun runDiagnosticWifi() = runDiagnostic {
        val sample = createDiagnosticJpeg()
        dispatcher.runTestWifiAlert(settings.value, sample)
    }

    fun runDiagnosticTelcoData() = runDiagnostic {
        val sample = createDiagnosticJpeg()
        dispatcher.runTestTelcoDataAlert(settings.value, sample)
    }

    fun runDiagnosticSms() = runDiagnostic {
        dispatcher.runTestFallbackSms(settings.value)
    }

    private fun runDiagnostic(block: suspend () -> DiagnosticTestResult) {
        if (_diagnosticRunning.value) return
        viewModelScope.launch {
            _diagnosticRunning.value = true
            val result = runCatching { block() }.getOrElse {
                DiagnosticTestResult("Diagnostic", false, it.message ?: "Unknown error")
            }
            _diagnosticLogs.value = listOf(result) + _diagnosticLogs.value.take(9)
            _diagnosticRunning.value = false
        }
    }

    private suspend fun createDiagnosticJpeg(): File = withContext(Dispatchers.IO) {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(15, 23, 42))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248)
            textSize = 28f
        }
        canvas.drawText("HomeAlert Diagnostic Snapshot", 40f, 160f, paint)
        paint.color = Color.WHITE
        paint.textSize = 20f
        canvas.drawText("Camera: ${settings.value.cameraLabel}", 40f, 210f, paint)

        val outFile = File(app.cacheDir, "diagnostic_test.jpg")
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos)
        }
        bmp.recycle()
        outFile
    }

    fun clearBanner() {
        _lastBannerMessage.value = null
    }

    override fun onCleared() {
        cameraController.shutdown()
        super.onCleared()
    }
}
