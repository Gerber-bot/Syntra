package com.syntra.app.domain.neural

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class GuardianSafetyLevel {
    SAFE,
    WARNING,
    CRITICAL
}

data class GuardianCheckResult(
    val safetyLevel: GuardianSafetyLevel,
    val canProceed: Boolean,
    val reason: String = "",
    val availableMemoryBytes: Long = 0L,
    val thermalStatus: Int = 0,
    val batteryPct: Int = 100,
    val isCharging: Boolean = false
)

object IrisXGuardian {

    // Minimum free RAM required before launching inference (600 MB safety floor)
    const val MIN_FREE_MEMORY_BYTES = 600L * 1024L * 1024L

    // Minimum battery level if not plugged in
    const val MIN_BATTERY_PERCENTAGE = 15

    fun evaluateResources(
        context: Context,
        modelSizeBytes: Long = 400L * 1024L * 1024L
    ): GuardianCheckResult {
        val memSafety = evaluateMemory(context, modelSizeBytes)
        val thermalSafety = evaluateThermal(context)
        val batterySafety = evaluateBattery(context)

        // Critical: low memory or severe thermal throttling
        if (memSafety.safetyLevel == GuardianSafetyLevel.CRITICAL) {
            return GuardianCheckResult(
                safetyLevel = GuardianSafetyLevel.CRITICAL,
                canProceed = false,
                reason = memSafety.reason,
                availableMemoryBytes = memSafety.availableMemoryBytes,
                thermalStatus = thermalSafety.thermalStatus,
                batteryPct = batterySafety.batteryPct,
                isCharging = batterySafety.isCharging
            )
        }

        if (thermalSafety.safetyLevel == GuardianSafetyLevel.CRITICAL) {
            return GuardianCheckResult(
                safetyLevel = GuardianSafetyLevel.CRITICAL,
                canProceed = false,
                reason = thermalSafety.reason,
                availableMemoryBytes = memSafety.availableMemoryBytes,
                thermalStatus = thermalSafety.thermalStatus,
                batteryPct = batterySafety.batteryPct,
                isCharging = batterySafety.isCharging
            )
        }

        if (batterySafety.safetyLevel == GuardianSafetyLevel.CRITICAL) {
            return GuardianCheckResult(
                safetyLevel = GuardianSafetyLevel.CRITICAL,
                canProceed = false,
                reason = batterySafety.reason,
                availableMemoryBytes = memSafety.availableMemoryBytes,
                thermalStatus = thermalSafety.thermalStatus,
                batteryPct = batterySafety.batteryPct,
                isCharging = batterySafety.isCharging
            )
        }

        val hasWarning = memSafety.safetyLevel == GuardianSafetyLevel.WARNING ||
                thermalSafety.safetyLevel == GuardianSafetyLevel.WARNING ||
                batterySafety.safetyLevel == GuardianSafetyLevel.WARNING

        val warningReason = listOf(memSafety.reason, thermalSafety.reason, batterySafety.reason)
            .filter { it.isNotBlank() }
            .joinToString("; ")

        return GuardianCheckResult(
            safetyLevel = if (hasWarning) GuardianSafetyLevel.WARNING else GuardianSafetyLevel.SAFE,
            canProceed = true,
            reason = warningReason,
            availableMemoryBytes = memSafety.availableMemoryBytes,
            thermalStatus = thermalSafety.thermalStatus,
            batteryPct = batterySafety.batteryPct,
            isCharging = batterySafety.isCharging
        )
    }

    private fun evaluateMemory(context: Context, modelSizeBytes: Long): GuardianCheckResult {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)

            val avail = memInfo.availMem
            val needed = modelSizeBytes + MIN_FREE_MEMORY_BYTES

            when {
                memInfo.lowMemory || avail < MIN_FREE_MEMORY_BYTES -> {
                    GuardianCheckResult(
                        safetyLevel = GuardianSafetyLevel.CRITICAL,
                        canProceed = false,
                        reason = "Memoria RAM insuficiente (${avail / (1024 * 1024)} MB libres). Riesgo de cierre por el sistema.",
                        availableMemoryBytes = avail
                    )
                }
                avail < needed -> {
                    GuardianCheckResult(
                        safetyLevel = GuardianSafetyLevel.WARNING,
                        canProceed = true,
                        reason = "Memoria libre ajustada (${avail / (1024 * 1024)} MB). Se recomienda limitar contexto.",
                        availableMemoryBytes = avail
                    )
                }
                else -> {
                    GuardianCheckResult(
                        safetyLevel = GuardianSafetyLevel.SAFE,
                        canProceed = true,
                        availableMemoryBytes = avail
                    )
                }
            }
        } catch (_: Exception) {
            GuardianCheckResult(GuardianSafetyLevel.SAFE, canProceed = true)
        }
    }

    private fun evaluateThermal(context: Context): GuardianCheckResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                return when {
                    status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                        GuardianCheckResult(
                            safetyLevel = GuardianSafetyLevel.CRITICAL,
                            canProceed = false,
                            reason = "Dispositivo caliente (estrés térmico severo). Inferencia pausada para proteger el hardware.",
                            thermalStatus = status
                        )
                    }
                    status >= PowerManager.THERMAL_STATUS_MODERATE -> {
                        GuardianCheckResult(
                            safetyLevel = GuardianSafetyLevel.WARNING,
                            canProceed = true,
                            reason = "Temperatura moderada detectada. Se sugiere reducir hilos.",
                            thermalStatus = status
                        )
                    }
                    else -> GuardianCheckResult(GuardianSafetyLevel.SAFE, canProceed = true, thermalStatus = status)
                }
            } catch (_: Exception) {
                // Ignore fallback
            }
        }
        return GuardianCheckResult(GuardianSafetyLevel.SAFE, canProceed = true)
    }

    private fun evaluateBattery(context: Context): GuardianCheckResult {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            when {
                !isCharging && pct < MIN_BATTERY_PERCENTAGE -> {
                    GuardianCheckResult(
                        safetyLevel = GuardianSafetyLevel.CRITICAL,
                        canProceed = false,
                        reason = "Batería baja ($pct%). Conectá el cargador para ejecutar la red neuronal.",
                        batteryPct = pct,
                        isCharging = false
                    )
                }
                !isCharging && pct < 25 -> {
                    GuardianCheckResult(
                        safetyLevel = GuardianSafetyLevel.WARNING,
                        canProceed = true,
                        reason = "Batería inferior al 25%. La inferencia neuronal consume energía intensiva.",
                        batteryPct = pct,
                        isCharging = false
                    )
                }
                else -> GuardianCheckResult(GuardianSafetyLevel.SAFE, canProceed = true, batteryPct = pct, isCharging = isCharging)
            }
        } catch (_: Exception) {
            GuardianCheckResult(GuardianSafetyLevel.SAFE, canProceed = true)
        }
    }
}
