package com.homealert.android.ui.screens

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.MonitoringState
import com.homealert.android.data.model.MotionTelemetry
import com.homealert.android.data.model.NetworkHealthState
import com.homealert.android.data.model.ZoneLayout
import com.homealert.android.ui.theme.AlertCrimson
import com.homealert.android.ui.theme.ArmedEmerald
import com.homealert.android.ui.theme.CardContainer
import com.homealert.android.ui.theme.FailoverAmber
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MonitorScreen(
    settings: AppSettings,
    monitoringState: MonitoringState,
    exitDelayRemaining: Int,
    telemetry: MotionTelemetry,
    networkHealth: NetworkHealthState,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onBindCamera: (androidx.lifecycle.LifecycleOwner, PreviewView?, Boolean) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onToggleMonitoring: () -> Unit,
    onActivateStealth: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, hasCameraPermission) {
        if (hasCameraPermission) {
            onBindCamera(lifecycleOwner, previewView, false)
        }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Network & Failover Status Badge Row
        val statusColor = when {
            networkHealth.wifiValidated -> ArmedEmerald
            networkHealth.cellularAvailable -> FailoverAmber
            else -> AlertCrimson
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardContainer)
                .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = statusColor)
                Column {
                    Text(
                        text = networkHealth.activeTierDescription,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                    Text(
                        text = "${settings.networkMode.label} • ${settings.alertChannel.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // CameraX Live Preview Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission required", color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRequestPermissions) {
                        Text("Grant Camera & Alert Permissions")
                    }
                }
            }

            // Exit Delay Countdown Overlay
            if (monitoringState == MonitoringState.EXIT_DELAY) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${exitDelayRemaining}s",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = FailoverAmber
                        )
                        Text(
                            text = "Exit room now — arming automatically",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Green Dual-Line Zone Overlay & Live Bounding Box (visible during Setup AND Armed)
            if (hasCameraPermission && monitoringState != MonitoringState.EXIT_DELAY) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val halfBand = 0.03f
                    val gridGreen = Color(0xFF22C55E).copy(alpha = 0.72f)
                    val bandFill = Color(0xFF22C55E).copy(alpha = 0.09f)
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

                    // Vertical dual-line hysteresis borders
                    for (k in 1 until settings.zoneLayout.cols) {
                        val center = k.toFloat() / settings.zoneLayout.cols.toFloat()
                        val x1 = (center - halfBand) * size.width
                        val x2 = (center + halfBand) * size.width
                        drawRect(
                            color = bandFill,
                            topLeft = Offset(x1, 0f),
                            size = Size(x2 - x1, size.height)
                        )
                        drawLine(
                            color = gridGreen,
                            start = Offset(x1, 0f),
                            end = Offset(x1, size.height),
                            strokeWidth = 2f,
                            pathEffect = dashEffect
                        )
                        drawLine(
                            color = gridGreen,
                            start = Offset(x2, 0f),
                            end = Offset(x2, size.height),
                            strokeWidth = 2f,
                            pathEffect = dashEffect
                        )
                    }

                    // Horizontal dual-line hysteresis border
                    for (r in 1 until settings.zoneLayout.rows) {
                        val center = r.toFloat() / settings.zoneLayout.rows.toFloat()
                        val y1 = (center - halfBand) * size.height
                        val y2 = (center + halfBand) * size.height
                        drawRect(
                            color = bandFill,
                            topLeft = Offset(0f, y1),
                            size = Size(size.width, y2 - y1)
                        )
                        drawLine(
                            color = gridGreen,
                            start = Offset(0f, y1),
                            end = Offset(size.width, y1),
                            strokeWidth = 2f,
                            pathEffect = dashEffect
                        )
                        drawLine(
                            color = gridGreen,
                            start = Offset(0f, y2),
                            end = Offset(size.width, y2),
                            strokeWidth = 2f,
                            pathEffect = dashEffect
                        )
                    }

                    // Live motion bounding box (Setup calibration + Armed)
                    telemetry.boundingBox?.let { box ->
                        val boxColor = when {
                            box.crossedFromLabel != null -> Color(0xFF22C55E)
                            box.inGuardband -> FailoverAmber
                            else -> Color(0xFF4ADE80)
                        }
                        val leftPx = box.left * size.width
                        val topPx = box.top * size.height
                        val widthPx = ((box.right - box.left) * size.width).coerceAtLeast(10f)
                        val heightPx = ((box.bottom - box.top) * size.height).coerceAtLeast(10f)

                        drawRect(
                            color = boxColor,
                            topLeft = Offset(leftPx, topPx),
                            size = Size(widthPx, heightPx),
                            style = Stroke(width = 3.5f)
                        )
                        drawCircle(
                            color = boxColor,
                            radius = 6f,
                            center = Offset(box.centroidX * size.width, box.centroidY * size.height)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val deltaText = String.format(Locale.US, "%.1f%%", telemetry.changedAreaPercent)
                    val zoneInfo = telemetry.boundingBox?.let { box ->
                        if (box.crossedFromLabel != null) {
                            "${box.crossedFromLabel}→${box.zoneLabel}"
                        } else {
                            box.zoneLabel
                        }
                    } ?: "Ready"
                    val modeTag = if (monitoringState == MonitoringState.ARMED) "ARMED" else "SETUP"
                    Text(
                        text = if (telemetry.globalExposureSuppressed) {
                            "$modeTag • Exposure Shift Filtered"
                        } else {
                            "$modeTag • Motion: $deltaText • $zoneInfo"
                        },
                        color = if (telemetry.globalExposureSuppressed) FailoverAmber else ArmedEmerald,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = if (monitoringState == MonitoringState.ARMED && telemetry.cooldownRemainingSeconds > 0) {
                            "Cooldown ${telemetry.cooldownRemainingSeconds}s"
                        } else {
                            settings.zoneLayout.label
                        },
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Camera Label & Detection Controls
        Card(
            colors = CardDefaults.cardColors(containerColor = CardContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = settings.cameraLabel,
                    onValueChange = { label -> onUpdateSettings { it.copy(cameraLabel = label) } },
                    label = { Text("Camera / Location Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Sensitivity Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sensitivity Threshold", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = String.format(Locale.US, "%.1f%% area", settings.sensitivityPercent),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = settings.sensitivityPercent,
                        onValueChange = { value ->
                            onUpdateSettings { it.copy(sensitivityPercent = (value * 2f).roundToInt() / 2f) }
                        },
                        valueRange = 1.0f..25.0f
                    )
                }

                // Cooldown Timer Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Snapshot Cooldown Timer", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${settings.cooldownSeconds}s",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = settings.cooldownSeconds.toFloat(),
                        onValueChange = { value ->
                            onUpdateSettings { it.copy(cooldownSeconds = value.roundToInt()) }
                        },
                        valueRange = 5f..120f
                    )
                }

                // Exit Delay Toggle (default 15s)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exit Delay Countdown (${settings.exitDelaySeconds}s)", fontWeight = FontWeight.Medium)
                        Text(
                            "Grace period after tapping Arm before motion triggers alerts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.exitDelayEnabled,
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(exitDelayEnabled = enabled) }
                        }
                    )
                }

                // Stealth / Blackout Auto-Activate Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stealth / Blackout Mode", fontWeight = FontWeight.Medium)
                        Text(
                            "Pure #000000 OLED screen + 0.01f brightness after arming",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.stealthAutoActivate,
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(stealthAutoActivate = enabled) }
                        }
                    )
                }

                // Region-Crossing Bounding Box Filter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require Region Crossing", fontWeight = FontWeight.Medium)
                        Text(
                            "Trigger when bounding box crosses both parallel lines of any border",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.requireRegionCrossing,
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(requireRegionCrossing = enabled) }
                        }
                    )
                }

                // Zone Layout Selector (4 Zones 2x2 vs 6 Zones 3x2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Zone Grid Layout", fontWeight = FontWeight.Medium)
                        Text(
                            "Dual-line hysteresis borders",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = settings.zoneLayout == ZoneLayout.FOUR_ZONES,
                            onClick = {
                                onUpdateSettings { it.copy(zoneLayout = ZoneLayout.FOUR_ZONES) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("4 (2×2)") }
                        SegmentedButton(
                            selected = settings.zoneLayout == ZoneLayout.SIX_ZONES,
                            onClick = {
                                onUpdateSettings { it.copy(zoneLayout = ZoneLayout.SIX_ZONES) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("6 (3×2)") }
                    }
                }

                // Flash Selector (Yes / No)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Camera Flash", fontWeight = FontWeight.Medium)
                        Text(
                            "Fire rear LED flash on capture",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = settings.deterrentFlashEnabled,
                            onClick = { onUpdateSettings { it.copy(deterrentFlashEnabled = true) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Yes") }
                        SegmentedButton(
                            selected = !settings.deterrentFlashEnabled,
                            onClick = { onUpdateSettings { it.copy(deterrentFlashEnabled = false) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("No") }
                    }
                }

                // Shutter Click Selector (Yes / No)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Shutter Click", fontWeight = FontWeight.Medium)
                        Text(
                            "Play audible shutter click on capture",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = settings.deterrentShutterSoundEnabled,
                            onClick = {
                                onUpdateSettings { it.copy(deterrentShutterSoundEnabled = true) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Yes") }
                        SegmentedButton(
                            selected = !settings.deterrentShutterSoundEnabled,
                            onClick = {
                                onUpdateSettings { it.copy(deterrentShutterSoundEnabled = false) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("No") }
                    }
                }
            }
        }

        // Action Row: Arm/Disarm + Instant Stealth Blackout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isMonitoring = monitoringState != MonitoringState.IDLE
            Button(
                onClick = onToggleMonitoring,
                enabled = hasCameraPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMonitoring) AlertCrimson else ArmedEmerald,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = if (isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (monitoringState) {
                        MonitoringState.IDLE -> "Start Monitoring"
                        MonitoringState.EXIT_DELAY -> "Cancel Arming (${exitDelayRemaining}s)"
                        MonitoringState.ARMED -> "Stop Monitoring"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                onClick = onActivateStealth,
                modifier = Modifier.height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.DarkMode, contentDescription = "Blackout")
            }
        }
    }
}
