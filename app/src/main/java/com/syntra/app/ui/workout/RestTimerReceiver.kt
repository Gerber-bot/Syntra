package com.syntra.app.ui.workout

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.syntra.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic event captured in the in-memory circular buffer.
 * Does not contain athlete personal data or PII.
 */
data class TimerLogEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val details: String
) {
    fun format(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return "[${sdf.format(Date(timestamp))}] $type: $details"
    }
}

/**
 * Thread-safe ring buffer storing the last 30 timer diagnostic events.
 */
object RestTimerDiagnostics {
    private const val MAX_LOGS = 30
    private val buffer = ArrayDeque<TimerLogEvent>(MAX_LOGS)

    @Synchronized
    fun log(type: String, details: String) {
        if (buffer.size >= MAX_LOGS) {
            buffer.removeFirst()
        }
        val event = TimerLogEvent(System.currentTimeMillis(), type, details)
        buffer.addLast(event)
        try {
            android.util.Log.d("SyntraTimer", "$type: $details")
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun getEvents(): List<TimerLogEvent> {
        return buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}

/**
 * Persistent state for the rest timer, surviving screen navigation and unmounts.
 */
data class RestTimerState(
    val totalSeconds: Int = 90,
    val secondsLeft: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val showCompletedFlash: Boolean = false,
    val targetElapsedMillis: Long? = null
)

/**
 * Central manager for active rest timer state.
 * Maintains reactive StateFlow and persists values to SharedPreferences.
 */
object RestTimerManager {
    private const val PREFS_NAME = "syntra_rest_timer_prefs"
    private const val KEY_TOTAL_SEC = "total_sec"
    private const val KEY_TARGET_ELAPSED = "target_elapsed"
    private const val KEY_PAUSED_SEC = "paused_sec"
    private const val KEY_IS_RUNNING = "is_running"

    private val _state = MutableStateFlow(RestTimerState())
    val state: StateFlow<RestTimerState> = _state.asStateFlow()

    fun initFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val totalSec = prefs.getInt(KEY_TOTAL_SEC, 90)
        val isRunning = prefs.getBoolean(KEY_IS_RUNNING, false)
        val targetElapsed = prefs.getLong(KEY_TARGET_ELAPSED, 0L)
        val pausedSec = prefs.getInt(KEY_PAUSED_SEC, 0)

        if (isRunning && targetElapsed > 0L) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val remMillis = targetElapsed - nowElapsed
            if (remMillis > 0) {
                val remSec = ((remMillis + 999) / 1000).toInt()
                _state.value = RestTimerState(
                    totalSeconds = totalSec,
                    secondsLeft = remSec,
                    isRunning = true,
                    targetElapsedMillis = targetElapsed
                )
                return
            } else {
                // Already expired while away
                _state.value = RestTimerState(
                    totalSeconds = totalSec,
                    secondsLeft = 0,
                    isRunning = false,
                    showCompletedFlash = true
                )
                clearPrefs(context)
                return
            }
        } else if (pausedSec > 0) {
            _state.value = RestTimerState(
                totalSeconds = totalSec,
                secondsLeft = pausedSec,
                isRunning = false,
                isPaused = true
            )
            return
        }

        _state.value = RestTimerState(totalSeconds = totalSec, secondsLeft = 0)
    }

    fun startTimer(context: Context, durationSec: Int) {
        val targetElapsed = SystemClock.elapsedRealtime() + (durationSec * 1000L)
        _state.value = RestTimerState(
            totalSeconds = durationSec,
            secondsLeft = durationSec,
            isRunning = true,
            isPaused = false,
            showCompletedFlash = false,
            targetElapsedMillis = targetElapsed
        )

        savePrefs(context, durationSec, targetElapsed, isRunning = true, pausedSec = 0)
        RestTimerAlarmScheduler.scheduleAlarm(context, durationSec, targetElapsed)
    }

    fun pauseTimer(context: Context) {
        val cur = _state.value
        if (cur.isRunning && cur.targetElapsedMillis != null) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val remSec = ((cur.targetElapsedMillis - nowElapsed + 999) / 1000).toInt().coerceAtLeast(0)
            _state.value = cur.copy(
                secondsLeft = remSec,
                isRunning = false,
                isPaused = true,
                targetElapsedMillis = null
            )
            savePrefs(context, cur.totalSeconds, targetElapsed = 0L, isRunning = false, pausedSec = remSec)
            RestTimerAlarmScheduler.cancelAlarm(context, reason = "user_pause")
        }
    }

    fun resumeTimer(context: Context) {
        val cur = _state.value
        val remSec = cur.secondsLeft
        if (remSec > 0) {
            val targetElapsed = SystemClock.elapsedRealtime() + (remSec * 1000L)
            _state.value = cur.copy(
                isRunning = true,
                isPaused = false,
                targetElapsedMillis = targetElapsed
            )
            savePrefs(context, cur.totalSeconds, targetElapsed, isRunning = true, pausedSec = 0)
            RestTimerAlarmScheduler.scheduleAlarm(context, remSec, targetElapsed)
        }
    }

    fun resetTimer(context: Context) {
        val cur = _state.value
        _state.value = RestTimerState(
            totalSeconds = cur.totalSeconds,
            secondsLeft = 0,
            isRunning = false,
            isPaused = false,
            showCompletedFlash = false,
            targetElapsedMillis = null
        )
        clearPrefs(context)
        RestTimerAlarmScheduler.cancelAlarm(context, reason = "user_reset")
    }

    fun addTime(context: Context, extraSeconds: Int) {
        val cur = _state.value
        if (cur.isRunning && cur.targetElapsedMillis != null) {
            val updatedTarget = cur.targetElapsedMillis + (extraSeconds * 1000L)
            val updatedTotal = cur.totalSeconds + extraSeconds
            val remSec = ((updatedTarget - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
            _state.value = cur.copy(
                totalSeconds = updatedTotal,
                secondsLeft = remSec,
                targetElapsedMillis = updatedTarget
            )
            savePrefs(context, updatedTotal, updatedTarget, isRunning = true, pausedSec = 0)
            RestTimerAlarmScheduler.scheduleAlarm(context, remSec, updatedTarget)
        } else if (cur.isPaused) {
            val updatedRem = (cur.secondsLeft + extraSeconds).coerceAtLeast(0)
            val updatedTotal = cur.totalSeconds + extraSeconds
            _state.value = cur.copy(
                totalSeconds = updatedTotal,
                secondsLeft = updatedRem
            )
            savePrefs(context, updatedTotal, targetElapsed = 0L, isRunning = false, pausedSec = updatedRem)
        } else {
            startTimer(context, extraSeconds)
        }
    }

    fun tickCountdown(context: Context) {
        val cur = _state.value
        if (!cur.isRunning || cur.targetElapsedMillis == null) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val remSec = ((cur.targetElapsedMillis - nowElapsed + 999) / 1000).toInt()

        if (remSec <= 0) {
            onTimerCompleted(context)
        } else {
            _state.value = cur.copy(secondsLeft = remSec)
        }
    }

    fun onTimerCompleted(context: Context) {
        val cur = _state.value
        _state.value = cur.copy(
            secondsLeft = 0,
            isRunning = false,
            isPaused = false,
            showCompletedFlash = true,
            targetElapsedMillis = null
        )
        clearPrefs(context)
    }

    fun dismissFlash() {
        _state.value = _state.value.copy(showCompletedFlash = false)
    }

    private fun savePrefs(context: Context, totalSec: Int, targetElapsed: Long, isRunning: Boolean, pausedSec: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_TOTAL_SEC, totalSec)
                .putLong(KEY_TARGET_ELAPSED, targetElapsed)
                .putBoolean(KEY_IS_RUNNING, isRunning)
                .putInt(KEY_PAUSED_SEC, pausedSec)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun clearPrefs(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_TARGET_ELAPSED)
                .remove(KEY_IS_RUNNING)
                .remove(KEY_PAUSED_SEC)
                .apply()
        } catch (_: Throwable) {}
    }
}

/**
 * BroadcastReceiver responsible for waking the device and triggering vibration & notification
 * even when the screen is turned off or Syntra is in the background.
 */
class RestTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REST_TIMER_EXPIRED) return

        val targetElapsed = intent.getLongExtra(EXTRA_TARGET_ELAPSED, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val driftMillis = if (targetElapsed > 0L) nowElapsed - targetElapsed else 0L

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenInteractive = powerManager?.isInteractive ?: true
        val canExact = RestTimerAlarmScheduler.canScheduleExactAlarms(context)

        RestTimerDiagnostics.log(
            "TIMER_RECEIVED",
            "driftMillis=${driftMillis}ms screenOn=$isScreenInteractive canExact=$canExact"
        )

        // Discard stale alarm that expired more than 60 seconds ago (avoid delayed wake-up vibrations)
        if (driftMillis > 60_000L) {
            RestTimerDiagnostics.log("TIMER_STALE", "Aviso descartado por expiración tardía (${driftMillis / 1000}s de demora)")
            RestTimerManager.onTimerCompleted(context)
            return
        }

        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val wakeLock = try {
                powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "syntra:rest_timer_wakelock")?.apply {
                    acquire(3000L)
                }
            } catch (e: Exception) {
                RestTimerDiagnostics.log("WAKELOCK_ERROR", e.message ?: "Failed to acquire WakeLock")
                null
            }

            try {
                // 1. Dual-path vibration with USAGE_ALARM attributes for screen-off & locked vibration
                val waveform = longArrayOf(0, 350, 150, 350)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    val vibrator = vibratorManager?.defaultVibrator
                    val effect = VibrationEffect.createWaveform(waveform, -1)
                    val attributes = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build()
                    vibrator?.vibrate(effect, attributes)
                    RestTimerDiagnostics.log("VIBRATION_REQUESTED", "VibratorManager USAGE_ALARM (screenOn=$isScreenInteractive)")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    val effect = VibrationEffect.createWaveform(waveform, -1)
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                    vibrator?.vibrate(effect, audioAttributes)
                    RestTimerDiagnostics.log("VIBRATION_REQUESTED", "Vibrator USAGE_ALARM (screenOn=$isScreenInteractive)")
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.vibrate(waveform, -1)
                    RestTimerDiagnostics.log("VIBRATION_REQUESTED", "Vibrator legacy (screenOn=$isScreenInteractive)")
                }

                // 2. High priority notification without sound (silent, vibration only)
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (notificationManager != null) {
                    val channelId = "syntra_rest_timer"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(
                            channelId,
                            "Temporizador de descanso",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "Avisos de finalización de tiempo de descanso"
                            enableVibration(true)
                            vibrationPattern = waveform
                            setSound(null, null)
                        }
                        notificationManager.createNotificationChannel(channel)
                    }

                    val notification = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Syntra — Descanso completado")
                        .setContentText("¡A darle con la siguiente serie!")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .build()

                    notificationManager.notify(NOTIFICATION_ID, notification)
                }

                // 3. Update central timer state
                RestTimerManager.onTimerCompleted(context)
                kotlinx.coroutines.delay(1000L)
            } catch (e: Throwable) {
                RestTimerDiagnostics.log("VIBRATION_ERROR", e.message ?: "Error during vibration/notification")
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Throwable) {}
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REST_TIMER_EXPIRED = "com.syntra.app.ACTION_REST_TIMER_EXPIRED"
        const val EXTRA_TARGET_ELAPSED = "extra_target_elapsed"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val NOTIFICATION_ID = 2001
    }
}

/**
 * Handles scheduling exact and inexact alarms using monotonic time base [AlarmManager.ELAPSED_REALTIME_WAKEUP].
 */
object RestTimerAlarmScheduler {
    private const val REQUEST_CODE = 4001

    fun scheduleAlarm(context: Context, durationSec: Int, targetElapsedRealtime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, RestTimerReceiver::class.java).apply {
            action = RestTimerReceiver.ACTION_REST_TIMER_EXPIRED
            putExtra(RestTimerReceiver.EXTRA_TARGET_ELAPSED, targetElapsedRealtime)
            putExtra(RestTimerReceiver.EXTRA_DURATION_SEC, durationSec)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        targetElapsedRealtime,
                        pendingIntent
                    )
                    RestTimerDiagnostics.log(
                        "TIMER_SCHEDULED",
                        "duration=${durationSec}s exact=true targetElapsed=$targetElapsedRealtime"
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        targetElapsedRealtime,
                        pendingIntent
                    )
                    RestTimerDiagnostics.log(
                        "TIMER_SCHEDULED",
                        "duration=${durationSec}s exact=false (inexact fallback, Doze may delay) targetElapsed=$targetElapsedRealtime"
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    targetElapsedRealtime,
                    pendingIntent
                )
                RestTimerDiagnostics.log(
                    "TIMER_SCHEDULED",
                    "duration=${durationSec}s exact=true (API M..R) targetElapsed=$targetElapsedRealtime"
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    targetElapsedRealtime,
                    pendingIntent
                )
                RestTimerDiagnostics.log(
                    "TIMER_SCHEDULED",
                    "duration=${durationSec}s exact=true (legacy API) targetElapsed=$targetElapsedRealtime"
                )
            }
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, targetElapsedRealtime, pendingIntent)
            RestTimerDiagnostics.log(
                "TIMER_SCHEDULED",
                "fallback set() due to exception: ${e.message}"
            )
        }
    }

    fun cancelAlarm(context: Context, reason: String = "cancel") {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, RestTimerReceiver::class.java).apply {
            action = RestTimerReceiver.ACTION_REST_TIMER_EXPIRED
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        try {
            alarmManager.cancel(pendingIntent)
            RestTimerDiagnostics.log("TIMER_CANCELED", "reason=$reason")
        } catch (e: Exception) {
            RestTimerDiagnostics.log("TIMER_CANCELED", "error canceling: ${e.message}")
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            return alarmManager?.canScheduleExactAlarms() ?: false
        }
        return true
    }

    fun openExactAlarmSetting(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}
