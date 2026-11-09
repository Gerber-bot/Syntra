package com.syntra.app.ui.workout

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.syntra.app.R

class RestTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REST_TIMER_EXPIRED) return

        // 1. Brief double-pulse vibration (Xiaomi & Doze friendly)
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
        } catch (_: Exception) {}

        // 2. High priority notification without sound (silent, vibration only)
        try {
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
                        vibrationPattern = longArrayOf(0, 350, 150, 350)
                        setSound(null, null) // Silent by default
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
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_REST_TIMER_EXPIRED = "com.syntra.app.ACTION_REST_TIMER_EXPIRED"
        const val NOTIFICATION_ID = 2001
    }
}

object RestTimerAlarmScheduler {
    private const val REQUEST_CODE = 4001

    fun scheduleAlarm(context: Context, targetWallTimeMillis: Long) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetWallTimeMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    targetWallTimeMillis,
                    pendingIntent
                )
            }
        } catch (_: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, targetWallTimeMillis, pendingIntent)
        }
    }

    fun cancelAlarm(context: Context) {
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
        } catch (_: Exception) {}
    }
}
