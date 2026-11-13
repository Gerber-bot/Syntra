package com.syntra.app.ui.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syntra.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RestTimerBar(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val timerState by RestTimerManager.state.collectAsState()

    var isExpanded by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingDurationSec by remember { mutableIntStateOf(90) }
    var permissionWarningDismissed by remember { mutableStateOf(false) }
    var notificationPermissionRequested by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Recover active timer state from persistent storage upon mounting
    LaunchedEffect(Unit) {
        RestTimerManager.initFromPrefs(context)
    }

    // Ticker driven by central manager
    LaunchedEffect(timerState.isRunning, timerState.targetElapsedMillis) {
        if (!timerState.isRunning || timerState.targetElapsedMillis == null) return@LaunchedEffect
        while (isActive && timerState.isRunning) {
            RestTimerManager.tickCountdown(context)
            delay(500L)
        }
    }

    // Auto-dismiss completed banner after 4 seconds
    LaunchedEffect(timerState.showCompletedFlash) {
        if (timerState.showCompletedFlash) {
            delay(4000L)
            RestTimerManager.dismissFlash()
        }
    }

    fun requestStart(durationSec: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !RestTimerAlarmScheduler.canScheduleExactAlarms(context) &&
            !permissionWarningDismissed
        ) {
            pendingDurationSec = durationSec
            showPermissionDialog = true
        } else {
            isExpanded = false
            RestTimerManager.startTimer(context, durationSec)
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showPermissionDialog = false
                permissionWarningDismissed = true
                isExpanded = false
                RestTimerManager.startTimer(context, pendingDurationSec)
            },
            title = {
                Text(
                    text = "Avisos de descanso puntuales",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SyntraColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = "En Android 14/15 y HyperOS, para que el temporizador vibre exactamente al terminar el descanso con la pantalla bloqueada o mientras usás otra app, Syntra requiere autorización de alarmas exactas.\n\nSin este permiso, el sistema operativo puede retrasar la vibración por ahorro de batería.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SyntraColors.TextSecondary
                )
            },
            confirmButton = {
                SyntraButton(
                    text = "Habilitar en Ajustes",
                    onClick = {
                        showPermissionDialog = false
                        permissionWarningDismissed = true
                        RestTimerAlarmScheduler.openExactAlarmSetting(context)
                        isExpanded = false
                        RestTimerManager.startTimer(context, pendingDurationSec)
                    }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        permissionWarningDismissed = true
                        isExpanded = false
                        RestTimerManager.startTimer(context, pendingDurationSec)
                    }
                ) {
                    Text("Continuar sin permiso", color = SyntraColors.TextMuted)
                }
            },
            containerColor = SyntraColors.SurfaceCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    SyntraCard(
        modifier = modifier.fillMaxWidth(),
        glowingBorder = timerState.isRunning
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⏱ Temporizador de descanso",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (timerState.showCompletedFlash) SyntraColors.NeonMint else SyntraColors.TextPrimary
                    )
                    if (timerState.isRunning) {
                        SyntraBadge(
                            text = "En curso",
                            containerColor = SyntraColors.DarkMint,
                            contentColor = SyntraColors.NeonMint
                        )
                    } else if (timerState.isPaused) {
                        SyntraBadge(
                            text = "Pausado",
                            containerColor = SyntraColors.SurfaceHighlight,
                            contentColor = SyntraColors.TextMuted
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showDiagnostics = !showDiagnostics },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = "⚙",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (showDiagnostics) SyntraColors.NeonMint else SyntraColors.TextMuted
                        )
                    }
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isExpanded) "Ocultar ▲" else "Presets ▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = SyntraColors.SoftMint
                        )
                    }
                }
            }

            if (timerState.showCompletedFlash) {
                Surface(
                    color = SyntraColors.DarkMint.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🔔 ¡Tiempo de descanso completado! A darle con la siguiente serie.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = SyntraColors.NeonMint,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Running or Paused Display
            if (timerState.isRunning || timerState.isPaused) {
                val displayMins = timerState.secondsLeft / 60
                val displaySecs = timerState.secondsLeft % 60
                val timeString = String.format(java.util.Locale.US, "%02d:%02d", displayMins, displaySecs)
                val progress = if (timerState.totalSeconds > 0) {
                    (timerState.secondsLeft.toFloat() / timerState.totalSeconds.toFloat()).coerceIn(0f, 1f)
                } else 0f

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = SyntraColors.NeonMint,
                    trackColor = SyntraColors.SurfaceHighlight
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (timerState.secondsLeft <= 10) SyntraColors.Error else SyntraColors.NeonMint
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                if (timerState.isRunning) {
                                    RestTimerManager.pauseTimer(context)
                                } else {
                                    RestTimerManager.resumeTimer(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (timerState.isRunning) SyntraColors.SurfaceHighlight else SyntraColors.NeonMint,
                                contentColor = if (timerState.isRunning) SyntraColors.TextPrimary else Color(0xFF05100E)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = if (timerState.isRunning) "Pausa" else "Reanudar",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { RestTimerManager.addTime(context, 15) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
                        ) {
                            Text("+15s", style = MaterialTheme.typography.labelSmall, color = SyntraColors.TextPrimary)
                        }

                        OutlinedButton(
                            onClick = { RestTimerManager.resetTimer(context) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
                        ) {
                            Text("✕", style = MaterialTheme.typography.labelSmall, color = SyntraColors.Error)
                        }
                    }
                }
            }

            // Quick Preset Buttons
            if (isExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(30, 60, 90, 120).forEach { sec ->
                        val isCurrentPreset = timerState.totalSeconds == sec && (timerState.isRunning || timerState.isPaused)
                        OutlinedButton(
                            onClick = { requestStart(sec) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isCurrentPreset) SyntraColors.NeonMint else SyntraColors.BorderSubtle
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isCurrentPreset) SyntraColors.DarkMint else SyntraColors.SurfaceCardElevated
                            )
                        ) {
                            Text(
                                text = "${sec}s",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrentPreset) SyntraColors.NeonMint else SyntraColors.TextPrimary
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { RestTimerManager.addTime(context, 30) },
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("+", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = SyntraColors.NeonMint)
                    }
                }
            }

            // Diagnostics & Perms Expandable Panel
            if (showDiagnostics) {
                val canExact = RestTimerAlarmScheduler.canScheduleExactAlarms(context)
                val events = remember(showDiagnostics) { RestTimerDiagnostics.getEvents().takeLast(6).reversed() }

                Surface(
                    color = Color(0xFF071110),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Diagnóstico de Alarmas & Vibración",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = SyntraColors.SoftMint
                            )
                            TextButton(
                                onClick = { RestTimerDiagnostics.clear() },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("Limpiar", style = MaterialTheme.typography.labelSmall, color = SyntraColors.TextMuted)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (canExact) "Alarma exacta: Habilitada" else "Alarma exacta: Inexacta (Requiere permiso)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (canExact) SyntraColors.NeonMint else SyntraColors.Error
                            )
                            if (!canExact) {
                                TextButton(
                                    onClick = { RestTimerAlarmScheduler.openExactAlarmSetting(context) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("Ajustes", style = MaterialTheme.typography.labelSmall, color = SyntraColors.NeonMint)
                                }
                            }
                        }

                        // Test button for the 3 scenarios (app visible, background, locked screen)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    RestTimerDiagnostics.log("TEST_INITIATED", "Iniciando prueba de 5 segundos...")
                                    RestTimerManager.startTimer(context, 5)
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.NeonMint),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Probar 5s (Verificar en Redmi)", style = MaterialTheme.typography.labelSmall, color = SyntraColors.NeonMint, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = "Guía: Tocá 'Probar 5s' y evaluá: (1) con app abierta, (2) minimizando a otra app, o (3) apagando la pantalla con el botón de bloqueo.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SyntraColors.TextMuted,
                            fontSize = 10.sp
                        )

                        Text(
                            text = "Últimos eventos registrados:",
                            style = MaterialTheme.typography.labelSmall,
                            color = SyntraColors.TextMuted
                        )

                        if (events.isEmpty()) {
                            Text(
                                text = "Sin eventos registrados aún.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SyntraColors.TextMuted
                            )
                        } else {
                            events.forEach { event ->
                                Text(
                                    text = event.format(),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = when {
                                        event.type.contains("ERROR") -> SyntraColors.Error
                                        event.type.contains("VIBRATION") -> SyntraColors.NeonMint
                                        else -> SyntraColors.TextSecondary
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
