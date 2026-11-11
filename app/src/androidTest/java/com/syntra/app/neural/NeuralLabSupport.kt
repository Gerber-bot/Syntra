package com.syntra.app.neural

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.syntra.app.domain.neural.ModelManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Diagnostic support for the isolated neural validation run (Syntra Lab).
 *
 * The report intentionally stores only technical data: timings, memory counters, token
 * counts, states and error class names. It never stores prompts, model output or any
 * personal information.
 */
object NeuralLabReport {

    const val LOG_TAG = "SyntraLab"

    private val startedAtMillis = System.currentTimeMillis()
    private val runStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAtMillis))
    private val lock = Any()

    @Volatile
    private var reportFile: File? = null

    fun file(context: Context): File = reportFile ?: synchronized(lock) {
        reportFile ?: run {
            val dir = (context.getExternalFilesDir("neural-lab")
                ?: File(context.filesDir, "neural-lab")).apply { mkdirs() }
            File(dir, "neural-lab-$runStamp.jsonl").also { reportFile = it }
        }
    }

    /**
     * Appends one JSON line per scenario. Appending (instead of rewriting) keeps earlier
     * results if the process dies during a later native call.
     */
    fun record(
        context: Context,
        scenario: String,
        status: String,
        metrics: Map<String, Any?> = emptyMap(),
        detail: String? = null
    ) {
        val json = JSONObject()
        json.put("timestamp", System.currentTimeMillis())
        json.put("scenario", scenario)
        json.put("status", status)
        json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        json.put("sdk", Build.VERSION.SDK_INT)
        json.put("appId", context.packageName)
        json.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        memory(context).forEach { (key, value) -> json.put(key, value) }
        metrics.forEach { (key, value) -> if (value != null) json.put(key, value) }
        if (detail != null) {
            json.put("detail", detail.take(400))
        }
        val line = json.toString()
        try {
            synchronized(lock) { file(context).appendText(line + "\n") }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "No se pudo escribir el reporte: ${t.javaClass.simpleName}")
        }
        Log.i(LOG_TAG, line)
    }

    @Suppress("DEPRECATION")
    fun memory(context: Context): Map<String, Long> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = activityManager
            ?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            ?.firstOrNull()
        val runtime = Runtime.getRuntime()
        return mapOf(
            "pssKb" to (info?.totalPss?.toLong() ?: -1L),
            "nativeHeapBytes" to Debug.getNativeHeapAllocatedSize(),
            "javaHeapUsedBytes" to (runtime.totalMemory() - runtime.freeMemory()),
            "javaHeapMaxBytes" to runtime.maxMemory()
        )
    }

    fun elapsedMillisSince(startedAt: Long): Long =
        (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
}

/**
 * Locates a real GGUF model without touching any other application's storage.
 *
 * Resolution order:
 * 1. Instrumentation argument `-e neuralModelPath <absolute path>`.
 * 2. The lab app's internal models directory (imported through the lab UI).
 * 3. The lab app's external models directory, which can be filled with `adb push`.
 *
 * Only files whose GGUF header is valid are considered; the largest one wins.
 */
object LabModelLocator {

    const val ARG_MODEL_PATH = "neuralModelPath"

    fun find(context: Context): File? {
        InstrumentationRegistry.getArguments()
            .getString(ARG_MODEL_PATH)
            ?.let { path -> File(path).takeIf { it.isFile && isUsable(it) } }
            ?.let { return it }

        val directories = buildList {
            add(File(context.filesDir, "models"))
            context.getExternalFilesDir("models")?.let { add(it) }
        }
        val candidates = directories.flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) } ?: emptyList()
        }
        return candidates
            .filter { isUsable(it) }
            .maxByOrNull { it.length() }
    }

    private fun isUsable(file: File): Boolean =
        file.length() > 8L && ModelManager.validateGgufFile(file).isSuccess
}

/** True when a Java string contains an unpaired UTF-16 surrogate. */
internal fun String.hasLoneSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return true
                index += 2
            }
            Character.isLowSurrogate(current) -> return true
            else -> index++
        }
    }
    return false
}

/** UTF-8 round-trip check used to detect replacement or broken characters. */
internal fun String.isUtf8Stable(): Boolean =
    !contains('\uFFFD') && !hasLoneSurrogate() &&
        String(toByteArray(Charsets.UTF_8), Charsets.UTF_8) == this

internal fun summarize(text: String): Map<String, Any> = mapOf(
    "chars" to text.length,
    "hasAccents" to text.any { it in '\u00C0'..'\u017F' },
    "hasEmoji" to text.codePoints().anyMatch { it > 0xFFFF },
    "hasReplacementChar" to text.contains('\uFFFD'),
    "loneSurrogate" to text.hasLoneSurrogate(),
    "utf8Stable" to text.isUtf8Stable()
)

internal fun tokensPerSecond(tokens: Int, elapsedMillis: Long): Double =
    if (tokens <= 0 || elapsedMillis <= 0L) 0.0 else (tokens * 1000.0) / max(elapsedMillis, 1L)
