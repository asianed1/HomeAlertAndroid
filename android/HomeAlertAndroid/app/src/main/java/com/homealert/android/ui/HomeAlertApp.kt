package com.homealert.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.homealert.android.data.model.MonitoringState
import com.homealert.android.ui.screens.GalleryScreen
import com.homealert.android.ui.screens.MonitorScreen
import com.homealert.android.ui.screens.SettingsScreen
import com.homealert.android.ui.screens.StealthBlackoutOverlay
import com.homealert.android.ui.theme.ElevatedSurface
import com.homealert.android.ui.theme.ObsidianCanvas

private enum class HomeAlertTab(val title: String, val icon: ImageVector) {
    MONITOR("Monitor", Icons.Default.Videocam),
    GALLERY("Gallery", Icons.Default.PhotoLibrary),
    SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAlertApp(
    viewModel: HomeAlertViewModel,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    onStealthBrightnessChanged: (Boolean) -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val monitoringState by viewModel.monitoringState.collectAsState()
    val exitDelayRemaining by viewModel.exitDelayRemaining.collectAsState()
    val stealthActive by viewModel.stealthActive.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val networkHealth by viewModel.networkHealth.collectAsState()
    val snapshots by viewModel.snapshots.collectAsState()
    val availableSims by viewModel.availableSims.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val diagnosticRunning by viewModel.diagnosticRunning.collectAsState()
    val lastBanner by viewModel.lastBannerMessage.collectAsState()

    var currentTab by remember { mutableStateOf(HomeAlertTab.MONITOR) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(monitoringState, stealthActive) {
        onKeepScreenOnChanged(monitoringState != MonitoringState.IDLE || stealthActive)
    }

    LaunchedEffect(stealthActive) {
        onStealthBrightnessChanged(stealthActive)
    }

    LaunchedEffect(lastBanner) {
        lastBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ObsidianCanvas,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("HomeAlert") },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = ElevatedSurface
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = ElevatedSurface) {
                    HomeAlertTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = {
                                currentTab = tab
                                if (tab == HomeAlertTab.GALLERY) viewModel.refreshGallery()
                                if (tab == HomeAlertTab.SETTINGS) {
                                    viewModel.refreshSims()
                                    viewModel.refreshNetworkHealth()
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    HomeAlertTab.MONITOR -> MonitorScreen(
                        settings = settings,
                        monitoringState = monitoringState,
                        exitDelayRemaining = exitDelayRemaining,
                        telemetry = telemetry,
                        networkHealth = networkHealth,
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermissions = onRequestPermissions,
                        onBindCamera = viewModel::bindCamera,
                        onUpdateSettings = viewModel::updateSettings,
                        onToggleMonitoring = viewModel::toggleMonitoring,
                        onActivateStealth = { viewModel.toggleStealthMode(true) }
                    )

                    HomeAlertTab.GALLERY -> GalleryScreen(
                        snapshots = snapshots,
                        onRefresh = viewModel::refreshGallery,
                        onDeleteSnapshot = viewModel::deleteSnapshot
                    )

                    HomeAlertTab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        availableSims = availableSims,
                        diagnosticLogs = diagnosticLogs,
                        diagnosticRunning = diagnosticRunning,
                        onUpdateSettings = viewModel::updateSettings,
                        onTestWifi = viewModel::runDiagnosticWifi,
                        onTestTelcoData = viewModel::runDiagnosticTelcoData,
                        onTestFallbackSms = viewModel::runDiagnosticSms,
                        onRequestRuntimePermissions = onRequestPermissions
                    )
                }
            }
        }

        if (stealthActive) {
            StealthBlackoutOverlay(
                monitoringState = monitoringState,
                networkHealth = networkHealth,
                onUnlock = { viewModel.toggleStealthMode(false) }
            )
        }
    }
}
