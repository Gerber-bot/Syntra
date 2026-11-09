package com.syntra.app.ui.workout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    var totalSeconds by remember { mutableIntStateOf(90) }
    var targetEndTimeWallMillis by remember { mutableStateOf<Long?>(null) }
    var pausedRemainingSeconds by remember { mutableStateOf<Int?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var showCompletedFlash by remember { mutableStateOf(false) }

    fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1))
                } else {
                    vibrator?.vibrate(longArrayOf(0, 350, 150, 350), -1)
                }
            }
        } catch (_: Exception) {
            // Ignore vibration error on devices without vibrator hardware
        }
    }

    var secondsLeft by remember { mutableIntStateOf(0) }

    // Timestamp-based countdown ticker
    LaunchedEffect(isRunning, targetEndTimeWallMillis) {
        if (!isRunning || targetEndTimeWallMillis == null) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis()
            val remaining = ((targetEndTimeWallMillis!! - now + 999) / 1000).toInt()
            if (remaining <= 0) {
                secondsLeft = 0
                isRunning = false
                targetEndTimeWallMillis = null
                pausedRemainingSeconds = null
                showCompletedFlash = true
                triggerVibration()
                break
            } else {
                secondsLeft = remaining
            }
            delay(500L)
        }
    }

    LaunchedEffect(showCompletedFlash) {
        if (showCompletedFlash) {
            delay(4000L)
            showCompletedFlash = false
        }
    }

    fun startTimer(durationSec: Int) {
        totalSeconds = durationSec
        secondsLeft = durationSec
        pausedRemainingSeconds = null
        targetEndTimeWallMillis = System.currentTimeMillis() + (durationSec * 1000L)
        isRunning = true
        showCompletedFlash = false
        isExpanded = true
    }

    fun pauseTimer() {
        if (isRunning && targetEndTimeWallMillis != null) {
            val now = System.currentTimeMillis()
            val rem = ((targetEndTimeWallMillis!! - now + 999) / 1000).toInt().coerceAtLeast(0)
            pausedRemainingSeconds = rem
            secondsLeft = rem
            isRunning = false
            targetEndTimeWallMillis = null
        }
    }

    fun resumeTimer() {
        val rem = pausedRemainingSeconds ?: secondsLeft
        if (rem > 0) {
            targetEndTimeWallMillis = System.currentTimeMillis() + (rem * 1000L)
            pausedRemainingSeconds = null
            isRunning = true
        }
    }

    fun resetTimer() {
        isRunning = false
        targetEndTimeWallMillis = null
        pausedRemainingSeconds = null
        secondsLeft = 0
        showCompletedFlash = false
    }

    fun addTime(extraSeconds: Int) {
        if (isRunning && targetEndTimeWallMillis != null) {
            targetEndTimeWallMillis = targetEndTimeWallMillis!! + (extraSeconds * 1000L)
            totalSeconds += extraSeconds
        } else if (pausedRemainingSeconds != null) {
            pausedRemainingSeconds = (pausedRemainingSeconds!! + extraSeconds).coerceAtLeast(0)
            secondsLeft = pausedRemainingSeconds!!
            totalSeconds += extraSeconds
        } else {
            startTimer(extraSeconds)
        }
    }

    SyntraCard(
        modifier = modifier.fillMaxWidth(),
        glowingBorder = isRunning
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
                        color = if (showCompletedFlash) SyntraColors.NeonMint else SyntraColors.TextPrimary
                    )
                    if (isRunning) {
                        SyntraBadge(
                            text = "En curso",
                            containerColor = SyntraColors.DarkMint,
                            contentColor = SyntraColors.NeonMint
                        )
                    }
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

            if (showCompletedFlash) {
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
            if (isRunning || pausedRemainingSeconds != null) {
                val displayMins = secondsLeft / 60
                val displaySecs = secondsLeft % 60
                val timeString = String.format("%02d:%02d", displayMins, displaySecs)
                val progress = if (totalSeconds > 0) (secondsLeft.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f) else 0f

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
                        color = if (secondsLeft <= 10) SyntraColors.Error else SyntraColors.NeonMint
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { if (isRunning) pauseTimer() else resumeTimer() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) SyntraColors.SurfaceHighlight else SyntraColors.NeonMint,
                                contentColor = if (isRunning) SyntraColors.TextPrimary else Color(0xFF05100E)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = if (isRunning) "Pausa" else "Reanudar",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { addTime(15) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle)
                        ) {
                            Text("+15s", style = MaterialTheme.typography.labelSmall, color = SyntraColors.TextPrimary)
                        }

                        OutlinedButton(
                            onClick = { resetTimer() },
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
            if (isExpanded || (!isRunning && pausedRemainingSeconds == null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(30, 60, 90, 120).forEach { sec ->
                        OutlinedButton(
                            onClick = { startTimer(sec) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (totalSeconds == sec && (isRunning || pausedRemainingSeconds != null)) SyntraColors.NeonMint else SyntraColors.BorderSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (totalSeconds == sec && (isRunning || pausedRemainingSeconds != null)) SyntraColors.DarkMint else SyntraColors.SurfaceCardElevated
                            )
                        ) {
                            Text(
                                text = "${sec}s",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (totalSeconds == sec && (isRunning || pausedRemainingSeconds != null)) SyntraColors.NeonMint else SyntraColors.TextPrimary
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { addTime(30) },
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SyntraColors.BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("+", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = SyntraColors.NeonMint)
                    }
                }
            }
        }
    }
}
