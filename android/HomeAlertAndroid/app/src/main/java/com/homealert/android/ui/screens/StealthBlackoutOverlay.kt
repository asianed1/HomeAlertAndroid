package com.homealert.android.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homealert.android.data.model.MonitoringState
import com.homealert.android.data.model.NetworkHealthState
import com.homealert.android.ui.theme.ArmedEmerald
import com.homealert.android.ui.theme.FailoverAmber

/**
 * Pure #000000 OLED Stealth / Blackout Mode overlay.
 * Paired with WindowManager screenBrightness = 0.01f and FLAG_KEEP_SCREEN_ON in MainActivity.
 * Requires double-tap or long-press to exit and restore normal screen brightness.
 */
@Composable
fun StealthBlackoutOverlay(
    monitoringState: MonitoringState,
    networkHealth: NetworkHealthState,
    onUnlock: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "stealthPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    val dotColor = when {
        monitoringState == MonitoringState.ARMED && networkHealth.wifiValidated -> ArmedEmerald
        monitoringState == MonitoringState.ARMED -> FailoverAmber
        else -> Color(0xFF38BDF8)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onUnlock() },
                    onLongPress = { onUnlock() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(dotAlpha)
                        .background(dotColor, CircleShape)
                )
                Text(
                    text = if (monitoringState == MonitoringState.ARMED) "STEALTH ARMED" else "STEALTH STANDBY",
                    color = Color(0xFF2B313C),
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Double-tap or long-press anywhere to unlock",
                color = Color(0xFF1E242D),
                fontSize = 11.sp
            )
        }
    }
}
