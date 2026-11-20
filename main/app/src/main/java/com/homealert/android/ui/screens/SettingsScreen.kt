package com.homealert.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.homealert.android.data.model.AlertChannel
import com.homealert.android.data.model.AppSettings
import com.homealert.android.data.model.DiagnosticTestResult
import com.homealert.android.data.model.NetworkMode
import com.homealert.android.data.model.SimSubscriptionInfo
import com.homealert.android.ui.theme.AlertCrimson
import com.homealert.android.ui.theme.ArmedEmerald
import com.homealert.android.ui.theme.CardContainer

@Composable
fun SettingsScreen(
    settings: AppSettings,
    availableSims: List<SimSubscriptionInfo>,
    diagnosticLogs: List<DiagnosticTestResult>,
    diagnosticRunning: Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onTestWifi: () -> Unit,
    onTestTelcoData: () -> Unit,
    onTestFallbackSms: () -> Unit,
    onRequestRuntimePermissions: () -> Unit
) {
    var showBotFatherDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Network Mode Selector
        Card(
            colors = CardDefaults.cardColors(containerColor = CardContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Network Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                NetworkMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdateSettings { it.copy(networkMode = mode) } }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.networkMode == mode,
                            onClick = { onUpdateSettings { it.copy(networkMode = mode) } }
                        )
                        Column(modifier = Modifier.padding(start = 6.dp)) {
                            Text(mode.label, fontWeight = FontWeight.Medium)
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 2. Alert Delivery Channel
        Card(
            colors = CardDefaults.cardColors(containerColor = CardContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Alert Channel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AlertChannel.entries.forEach { channel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdateSettings { it.copy(alertChannel = channel) } },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.alertChannel == channel,
                            onClick = { onUpdateSettings { it.copy(alertChannel = channel) } }
                        )
                        Text(channel.label, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        // 3. Telegram Configuration
        if (settings.alertChannel == AlertChannel.TELEGRAM || settings.alertChannel == AlertChannel.BOTH) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Telegram Bot Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { showBotFatherDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "@BotFather Setup Guide")
                        }
                    }
                    OutlinedTextField(
                        value = settings.telegramBotToken,
                        onValueChange = { v -> onUpdateSettings { it.copy(telegramBotToken = v) } },
                        label = { Text("Bot Token (e.g. 123456:ABC-DEF...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.telegramChatId,
                        onValueChange = { v -> onUpdateSettings { it.copy(telegramChatId = v) } },
                        label = { Text("Chat ID (numeric user or group ID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 4. Gmail SMTP Configuration
        if (settings.alertChannel == AlertChannel.GMAIL || settings.alertChannel == AlertChannel.BOTH) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Gmail TLS SMTP Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = settings.gmailSender,
                        onValueChange = { v -> onUpdateSettings { it.copy(gmailSender = v) } },
                        label = { Text("Sender Gmail Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.gmailAppPassword,
                        onValueChange = { v -> onUpdateSettings { it.copy(gmailAppPassword = v) } },
                        label = { Text("16-char Google App Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.gmailRecipients,
                        onValueChange = { v -> onUpdateSettings { it.copy(gmailRecipients = v) } },
                        label = { Text("Recipient Email(s), comma-separated") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 5. Tier 3 SMS Fallback & Dual-SIM Selection
        if (settings.networkMode == NetworkMode.SMART_AUTO_FAILOVER) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Telco SMS Last-Resort Fallback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Protected by a 90s SMS anti-flood guard regardless of snapshot cooldown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = settings.smsFallbackNumber,
                        onValueChange = { v -> onUpdateSettings { it.copy(smsFallbackNumber = v) } },
                        label = { Text("Homeowner Phone Number (e.g. +15551234567)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (availableSims.isNotEmpty()) {
                        Text("Active SIM Subscription", style = MaterialTheme.typography.labelLarge)
                        availableSims.forEach { sim ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onUpdateSettings { it.copy(selectedSimSubId = sim.subscriptionId) } },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = settings.selectedSimSubId == sim.subscriptionId ||
                                        (settings.selectedSimSubId == -1 && sim == availableSims.first()),
                                    onClick = { onUpdateSettings { it.copy(selectedSimSubId = sim.subscriptionId) } }
                                )
                                Text("SIM ${sim.slotIndex + 1}: ${sim.displayName} (${sim.carrierName})")
                            }
                        }
                    }
                }
            }
        }

        // 6. Pre-Arm Diagnostics & Permissions
        Card(
            colors = CardDefaults.cardColors(containerColor = CardContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Pre-Arm Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = onTestWifi,
                    enabled = !diagnosticRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Wi-Fi Alert")
                }
                Button(
                    onClick = onTestTelcoData,
                    enabled = !diagnosticRunning && settings.networkMode == NetworkMode.SMART_AUTO_FAILOVER,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Telco Data Alert")
                }
                Button(
                    onClick = onTestFallbackSms,
                    enabled = !diagnosticRunning && settings.networkMode == NetworkMode.SMART_AUTO_FAILOVER,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Fallback SMS")
                }
                OutlinedButton(
                    onClick = onRequestRuntimePermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check / Grant Runtime Permissions")
                }

                if (diagnosticLogs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    diagnosticLogs.forEach { log ->
                        Text(
                            text = "${if (log.success) "PASS" else "FAIL"} • ${log.tierName}: ${log.message}",
                            color = if (log.success) ArmedEmerald else AlertCrimson,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (showBotFatherDialog) {
        AlertDialog(
            onDismissRequest = { showBotFatherDialog = false },
            title = { Text("@BotFather Setup Guide") },
            text = {
                Text(
                    "1. Open Telegram and message @BotFather.\n" +
                        "2. Send /newbot and follow prompts to copy your Bot Token.\n" +
                        "3. Start a chat with your new bot and send /start.\n" +
                        "4. Message @userinfobot (or call getUpdates) to copy your numeric Chat ID."
                )
            },
            confirmButton = {
                TextButton(onClick = { showBotFatherDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }
}
