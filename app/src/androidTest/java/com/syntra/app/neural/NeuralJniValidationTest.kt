package com.syntra.app.neural

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.llama.cpp.LLamaAndroid
import com.syntra.app.domain.neural.EngineState
import com.syntra.app.domain.neural.GenerationChunk
import com.syntra.app.domain.neural.InferenceParams
import com.syntra.app.domain.neural.LlamaCppInferenceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Real JNI validation of the ARM64 llama.cpp engine inside the isolated Syntra Lab build.
 *
 * Requirements on the device:
 * - ARM64 device with the lab APK installed (`com.syntra.app.lab`).
 * - A Qwen3 GGUF model reachable by [LabModelLocator]; otherwise tests are skipped.
 *
 * Every scenario writes a JSON line to the lab report and to logcat tag `SyntraLab`.
 * Only technical metrics are recorded: no prompts, no generated text, no user data.
 * A native crash would kill the process; earlier lines stay on disk by design.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NeuralJniValidationTest {

    private lateinit var context: Context
    private lateinit var engine: LlamaCppInferenceEngine

    private val prompt = "Responde en una sola frase corta: ¿qué es una sentadilla?"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = LlamaCppInferenceEngine()
    }

    // Block body on purpose: JUnit 4 requires a JVM `void` return for @Before/@After.
    // With an expression body, the `catch` branch returns the Int from Log.w, so
    // runBlocking would be inferred as Any and the method would compile to
    // `()Ljava/lang/Object;`, which makes the runner reject the class.
    @After
    fun tearDown() {
        runBlocking {
            try {
                withTimeout(120_000) { engine.unloadModel() }
            } catch (t: Throwable) {
                android.util.Log.w(NeuralLabReport.LOG_TAG, "tearDown unload: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun requireNative(scenario: String) {
        val available = LlamaCppInferenceEngine.isNativeAvailable()
        NeuralLabReport.record(
            context,
            scenario,
            if (available) "INFO" else "SKIP",
            mapOf(
                "nativeAvailable" to available,
                "nativeError" to (LlamaCppInferenceEngine.getNativeLoadError() ?: "none"),
                "nativeState" to LLamaAndroid.instance().getCurrentState().toString()
            )
        )
        Assume.assumeTrue("La biblioteca JNI ARM64 no está disponible en este dispositivo", available)
    }

    private fun requireModel(scenario: String): File {
        val model = LabModelLocator.find(context)
        NeuralLabReport.record(
            context,
            scenario,
            if (model == null) "SKIP" else "INFO",
            mapOf(
                "modelName" to (model?.name ?: "none"),
                "modelBytes" to (model?.length() ?: 0L),
                "searchedDirs" to LabModelLocator.describeDirectories(context)
            ),
            if (model == null) "No hay modelo GGUF en el laboratorio" else null
        )
        Assume.assumeTrue(
            "No hay modelo GGUF en el laboratorio. Ver docs/VALIDACION_NEURAL_ANDROID.md",
            model != null
        )
        return model!!
    }

    private suspend fun loadReady(model: File) {
        val result = withTimeout(180_000) { engine.loadModel(model, threads = 4) }
        assertTrue("loadModel failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(EngineState.READY, engine.state)
        assertTrue(engine.isModelLoaded())
    }

    private data class GenerationRun(
        val chunks: List<GenerationChunk>,
        val text: String,
        val firstTokenMillis: Long,
        val totalMillis: Long
    ) {
        val terminalChunks: List<GenerationChunk> get() = chunks.filter { it.isComplete }
        val tokenChunks: List<GenerationChunk> get() = chunks.filter { !it.isComplete && it.text.isNotEmpty() }
    }

    private suspend fun collectGeneration(
        textPrompt: String = prompt,
        maxTokens: Int = 24,
        onFirstTextChunk: (suspend () -> Unit)? = null
    ): GenerationRun {
        val startedAt = SystemClock.elapsedRealtime()
        val chunks = mutableListOf<GenerationChunk>()
        val text = StringBuilder()
        var firstTokenMillis = -1L
        engine.generate(textPrompt, InferenceParams(maxTokens = maxTokens)).collect { chunk ->
            chunks += chunk
            if (!chunk.isComplete && chunk.text.isNotEmpty()) {
                if (firstTokenMillis < 0) {
                    firstTokenMillis = NeuralLabReport.elapsedMillisSince(startedAt)
                }
                text.append(chunk.text)
                onFirstTextChunk?.invoke()
            }
        }
        return GenerationRun(
            chunks = chunks,
            text = text.toString(),
            firstTokenMillis = if (firstTokenMillis < 0) -1L else firstTokenMillis,
            totalMillis = NeuralLabReport.elapsedMillisSince(startedAt)
        )
    }

    private fun nativeStateName(): String = LLamaAndroid.instance().getCurrentState().toString()

    @Test
    fun test01_preflight_reports_native_and_model() = runBlocking {
        requireNative("preflight")
        val model = LabModelLocator.find(context)
        NeuralLabReport.record(
            context,
            "preflight",
            "PASS",
            mapOf(
                "nativeAvailable" to true,
                "nativeState" to nativeStateName(),
                "modelFound" to (model != null),
                "modelName" to (model?.name ?: "none"),
                "modelBytes" to (model?.length() ?: 0L),
                "modelPath" to (model?.absolutePath ?: "none"),
                "searchedDirs" to LabModelLocator.describeDirectories(context),
                "engineState" to engine.state.name
            )
        )
        assertFalse(LLamaAndroid.instance().hasModelLoaded())
    }

    @Test
    fun test02_loads_valid_gguf_and_reports_metrics() = runBlocking {
        requireNative("load_valid_gguf")
        val model = requireModel("load_valid_gguf")
        val startedAt = SystemClock.elapsedRealtime()

        val result = withTimeout(180_000) { engine.loadModel(model, threads = 4) }
        val elapsed = NeuralLabReport.elapsedMillisSince(startedAt)

        NeuralLabReport.record(
            context,
            "load_valid_gguf",
            if (result.isSuccess && engine.state == EngineState.READY) "PASS" else "FAIL",
            mapOf(
                "loadMillis" to elapsed,
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName(),
                "isModelLoaded" to engine.isModelLoaded(),
                "modelName" to engine.metrics.modelName,
                "modelBytes" to engine.metrics.modelSizeBytes
            ),
            result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        )

        assertTrue("loadModel failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(EngineState.READY, engine.state)
        assertTrue(engine.isModelLoaded())
        assertEquals(model.name, engine.metrics.modelName)
    }

    @Test
    fun test03_first_generation_reports_ttft_and_single_terminal_chunk() = runBlocking {
        requireNative("first_generation")
        val model = requireModel("first_generation")
        loadReady(model)

        val run = withTimeout(180_000) { collectGeneration(prompt, maxTokens = 24) }

        val terminal = run.terminalChunks
        NeuralLabReport.record(
            context,
            "first_generation",
            if (run.text.isNotBlank() && terminal.size == 1) "PASS" else "FAIL",
            mapOf(
                "ttftMillis" to run.firstTokenMillis,
                "totalMillis" to run.totalMillis,
                "tokens" to run.tokenChunks.size,
                "tokensPerSecond" to tokensPerSecond(run.tokenChunks.size, run.totalMillis),
                "terminalChunks" to terminal.size,
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName(),
                "utf8" to run.text.isUtf8Stable()
            )
        )

        assertTrue("No text was generated", run.text.isNotBlank())
        assertEquals("A normal generation must emit exactly one terminal chunk", 1, terminal.size)
        assertTrue(run.text.isUtf8Stable())
        assertEquals(EngineState.READY, engine.state)
    }

    @Test
    fun test04_consecutive_generations_stay_consistent() = runBlocking {
        requireNative("consecutive_generations")
        val model = requireModel("consecutive_generations")
        loadReady(model)

        val runs = mutableListOf<GenerationRun>()
        repeat(3) { index ->
            runs += withTimeout(180_000) { collectGeneration(prompt, maxTokens = 16) }
            assertEquals("Generation ${index + 1} must end READY", EngineState.READY, engine.state)
        }

        val allText = runs.joinToString(separator = "") { it.text }
        val singleTerminal = runs.all { it.terminalChunks.size == 1 }
        NeuralLabReport.record(
            context,
            "consecutive_generations",
            if (singleTerminal && allText.isNotBlank()) "PASS" else "FAIL",
            mapOf(
                "generations" to runs.size,
                "totalTokens" to runs.sumOf { it.tokenChunks.size },
                "singleTerminalEach" to singleTerminal,
                "ttftMillis" to runs.map { it.firstTokenMillis },
                "totalMillis" to runs.map { it.totalMillis },
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName()
            )
        )

        assertTrue(allText.isNotBlank())
        assertTrue("Every generation must emit exactly one terminal chunk", singleTerminal)
    }

    @Test
    fun test05_cancel_during_generation_terminates_cleanly() = runBlocking {
        requireNative("cancel_during_generation")
        val model = requireModel("cancel_during_generation")
        loadReady(model)

        val firstChunk = CompletableDeferred<Unit>()
        val chunks = mutableListOf<GenerationChunk>()
        val collector = launch(Dispatchers.Default) {
            engine.generate(prompt, InferenceParams(maxTokens = 128)).collect { chunk ->
                chunks += chunk
                if (!chunk.isComplete) firstChunk.complete(Unit)
            }
        }

        withTimeout(60_000) { firstChunk.await() }
        val cancelRequestedAt = SystemClock.elapsedRealtime()
        engine.cancel()
        withTimeout(60_000) { collector.join() }
        val cancelMillis = NeuralLabReport.elapsedMillisSince(cancelRequestedAt)

        val terminal = chunks.filter { it.isComplete }
        val cancelMessage = terminal.singleOrNull()?.errorMessage
        NeuralLabReport.record(
            context,
            "cancel_during_generation",
            if (terminal.size == 1 && cancelMessage != null) "PASS" else "FAIL",
            mapOf(
                "cancelMillis" to cancelMillis,
                "terminalChunks" to terminal.size,
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName(),
                "errorClass" to if (cancelMessage != null) "cancellation" else "none"
            )
        )

        assertEquals("Cancellation must emit exactly one terminal chunk", 1, terminal.size)
        assertTrue(
            "Terminal chunk must report cancellation, was: ${terminal.singleOrNull()?.errorMessage}",
            cancelMessage?.contains("cancelada", ignoreCase = true) == true
        )
        assertEquals(EngineState.READY, engine.state)

        // The engine must remain usable after a cancellation.
        val recovery = withTimeout(180_000) { collectGeneration(prompt, maxTokens = 16) }
        assertTrue("Generation after cancellation must produce text", recovery.text.isNotBlank())
    }

    @Test
    fun test06_cancel_while_consumer_holds_emission() = runBlocking {
        requireNative("cancel_suspended_emission")
        val model = requireModel("cancel_suspended_emission")
        loadReady(model)

        val firstChunk = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val chunks = mutableListOf<GenerationChunk>()
        val terminalAt = CompletableDeferred<Long>()
        val collector = launch(Dispatchers.Default) {
            engine.generate(prompt, InferenceParams(maxTokens = 128)).collect { chunk ->
                chunks += chunk
                if (!chunk.isComplete) {
                    if (!firstChunk.isCompleted) firstChunk.complete(Unit)
                    releaseConsumer.await()
                } else {
                    terminalAt.complete(SystemClock.elapsedRealtime())
                }
            }
        }

        withTimeout(60_000) { firstChunk.await() }
        delay(150)
        val cancelRequestedAt = SystemClock.elapsedRealtime()
        engine.cancel()
        delay(400)
        val releaseAt = SystemClock.elapsedRealtime()
        releaseConsumer.complete(Unit)

        val terminalTime = withTimeout(60_000) { terminalAt.await() }
        withTimeout(60_000) { collector.join() }

        val terminal = chunks.filter { it.isComplete }
        NeuralLabReport.record(
            context,
            "cancel_suspended_emission",
            if (terminal.size == 1) "PASS" else "FAIL",
            mapOf(
                "cancelToTerminalMillis" to (terminalTime - cancelRequestedAt),
                "releaseToTerminalMillis" to (terminalTime - releaseAt),
                "terminalChunks" to terminal.size,
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName()
            )
        )

        assertEquals("Cancellation must emit exactly one terminal chunk", 1, terminal.size)
        assertEquals(EngineState.READY, engine.state)

        // Cleanup must still release the native session after a held emission.
        withTimeout(120_000) { engine.unloadModel() }
        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertFalse(LLamaAndroid.instance().hasModelLoaded())
    }

    /**
     * Matches the real UI cancel path: the collecting job is cancelled by the caller while
     * the native producer is suspended in emit, at the same time as the cooperative native
     * cancellation. No resource may be freed while the producer can still use it.
     */
    @Test
    fun test06b_cancel_collector_while_emission_suspended() = runBlocking {
        requireNative("cancel_collector_suspended")
        val model = requireModel("cancel_collector_suspended")
        loadReady(model)

        val firstChunk = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val chunks = mutableListOf<GenerationChunk>()
        val collector = launch(Dispatchers.Default) {
            engine.generate(prompt, InferenceParams(maxTokens = 128)).collect { chunk ->
                chunks += chunk
                if (!chunk.isComplete) {
                    if (!firstChunk.isCompleted) firstChunk.complete(Unit)
                    releaseConsumer.await()
                }
            }
        }

        withTimeout(60_000) { firstChunk.await() }
        delay(150)

        val cancelRequestedAt = SystemClock.elapsedRealtime()
        engine.cancel()
        collector.cancel()
        withTimeout(60_000) { collector.join() }
        val collectorCancelMillis = NeuralLabReport.elapsedMillisSince(cancelRequestedAt)

        // Give the producer a bounded window to finish its own cleanup.
        delay(1_000)
        val stateAfterCancel = engine.state

        val unloadStartedAt = SystemClock.elapsedRealtime()
        withTimeout(120_000) { engine.unloadModel() }
        val unloadMillis = NeuralLabReport.elapsedMillisSince(unloadStartedAt)

        NeuralLabReport.record(
            context,
            "cancel_collector_suspended",
            if (stateAfterCancel != EngineState.GENERATING &&
                engine.state == EngineState.UNINITIALIZED &&
                !LLamaAndroid.instance().hasModelLoaded()
            ) "PASS" else "FAIL",
            mapOf(
                "collectorCancelMillis" to collectorCancelMillis,
                "stateAfterCancel" to stateAfterCancel.name,
                "stateAfterUnload" to engine.state.name,
                "unloadMillis" to unloadMillis,
                "nativeHasModel" to LLamaAndroid.instance().hasModelLoaded(),
                "chunksBeforeCancel" to chunks.size
            ),
            "La cancelación del colector no debe dejar GENERATING ni impedir el unload"
        )

        assertTrue(
            "Cancelling the collector must not leave the engine in GENERATING",
            stateAfterCancel != EngineState.GENERATING
        )
        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertFalse(LLamaAndroid.instance().hasModelLoaded())
    }

    @Test
    fun test07_concurrent_unloads_release_once() = runBlocking {
        requireNative("concurrent_unloads")
        val model = requireModel("concurrent_unloads")
        loadReady(model)

        val startedAt = SystemClock.elapsedRealtime()
        val first = async(Dispatchers.IO) { engine.unloadModel() }
        val second = async(Dispatchers.IO) { engine.unloadModel() }
        withTimeout(120_000) {
            first.await()
            second.await()
        }
        val elapsed = NeuralLabReport.elapsedMillisSince(startedAt)

        NeuralLabReport.record(
            context,
            "concurrent_unloads",
            if (engine.state == EngineState.UNINITIALIZED && !LLamaAndroid.instance().hasModelLoaded()) "PASS" else "FAIL",
            mapOf(
                "unloadMillis" to elapsed,
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName(),
                "nativeHasModel" to LLamaAndroid.instance().hasModelLoaded()
            ),
            "El doble free se detectaría como caída del proceso o estado inconsistente"
        )

        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertFalse(LLamaAndroid.instance().hasModelLoaded())
    }

    @Test
    fun test08_unload_and_reload_recovers() = runBlocking {
        requireNative("unload_reload")
        val model = requireModel("unload_reload")

        loadReady(model)
        val first = withTimeout(180_000) { collectGeneration(prompt, maxTokens = 16) }
        assertTrue(first.text.isNotBlank())

        val unloadStartedAt = SystemClock.elapsedRealtime()
        withTimeout(120_000) { engine.unloadModel() }
        val unloadMillis = NeuralLabReport.elapsedMillisSince(unloadStartedAt)
        assertEquals(EngineState.UNINITIALIZED, engine.state)
        assertFalse(LLamaAndroid.instance().hasModelLoaded())

        val reloadStartedAt = SystemClock.elapsedRealtime()
        loadReady(model)
        val reloadMillis = NeuralLabReport.elapsedMillisSince(reloadStartedAt)
        val second = withTimeout(180_000) { collectGeneration(prompt, maxTokens = 16) }

        NeuralLabReport.record(
            context,
            "unload_reload",
            if (second.text.isNotBlank() && engine.state == EngineState.READY) "PASS" else "FAIL",
            mapOf(
                "unloadMillis" to unloadMillis,
                "reloadMillis" to reloadMillis,
                "postUnloadState" to EngineState.UNINITIALIZED.name,
                "finalState" to engine.state.name,
                "nativeState" to nativeStateName()
            )
        )

        assertTrue("Reloaded engine must generate text", second.text.isNotBlank())
        assertEquals(EngineState.READY, engine.state)
    }

    @Test
    fun test09_unload_requested_during_load_leaves_coherent_state() = runBlocking {
        requireNative("unload_during_load")
        val model = requireModel("unload_during_load")

        val loadJob = async(Dispatchers.IO) { engine.loadModel(model, threads = 4) }
        delay(400)

        val unloadStartedAt = SystemClock.elapsedRealtime()
        val unloadJob = async(Dispatchers.IO) { engine.unloadModel() }
        withTimeout(180_000) { unloadJob.await() }
        val unloadMillis = NeuralLabReport.elapsedMillisSince(unloadStartedAt)
        val loadResult = withTimeout(180_000) { loadJob.await() }

        val nativeHasModel = LLamaAndroid.instance().hasModelLoaded()
        val coherent = if (engine.state == EngineState.READY) {
            engine.isModelLoaded() && nativeHasModel
        } else {
            !nativeHasModel
        }

        NeuralLabReport.record(
            context,
            "unload_during_load",
            if (coherent) "PASS" else "FAIL",
            mapOf(
                "unloadMillis" to unloadMillis,
                "loadSucceeded" to loadResult.isSuccess,
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName(),
                "nativeHasModel" to nativeHasModel,
                "isModelLoaded" to engine.isModelLoaded()
            ),
            loadResult.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        )

        assertTrue("Engine and native ownership must agree after unload-during-load", coherent)

        // A later load must always recover the engine.
        withTimeout(120_000) { engine.unloadModel() }
        loadReady(model)
        assertEquals(EngineState.READY, engine.state)
    }

    @Test
    fun test10_excessive_context_is_reported_to_kotlin() = runBlocking {
        requireNative("excessive_context")
        val model = requireModel("excessive_context")
        loadReady(model)

        val oversizedPrompt = "hola ".repeat(20_000)
        val run = withTimeout(180_000) { collectGeneration(oversizedPrompt, maxTokens = 16) }
        val terminal = run.terminalChunks
        val errorMessage = terminal.singleOrNull()?.errorMessage

        NeuralLabReport.record(
            context,
            "excessive_context",
            if (terminal.size == 1 && errorMessage != null) "PASS" else "FAIL",
            mapOf(
                "promptChars" to oversizedPrompt.length,
                "terminalChunks" to terminal.size,
                "errorClass" to errorMessage?.let { if (it.contains("excede", true)) "context_overflow" else "native_error" },
                "engineState" to engine.state.name,
                "nativeState" to nativeStateName(),
                "totalMillis" to run.totalMillis
            )
        )

        assertEquals("Context overflow must emit exactly one terminal chunk", 1, terminal.size)
        assertNotNull("Context overflow must reach Kotlin as an error", errorMessage)
        assertEquals(EngineState.ERROR, engine.state)

        // Recovery path used by the UI: unload, then load again.
        withTimeout(120_000) { engine.unloadModel() }
        loadReady(model)
        assertEquals(EngineState.READY, engine.state)
    }

    @Test
    fun test11_streaming_utf8_is_stable() = runBlocking {
        requireNative("utf8_streaming")
        val model = requireModel("utf8_streaming")
        loadReady(model)

        val accentedPrompt =
            "Escribe una frase breve en español con tildes y una vocal acentuada; responde solo la frase."
        val run = withTimeout(180_000) { collectGeneration(accentedPrompt, maxTokens = 32) }
        val perChunkStable = run.chunks.filter { !it.isComplete }.all { it.text.isUtf8Stable() }

        NeuralLabReport.record(
            context,
            "utf8_streaming",
            if (run.text.isUtf8Stable() && perChunkStable && run.text.isNotBlank()) "PASS" else "FAIL",
            mapOf(
                "chars" to run.text.length,
                "hasAccents" to summarize(run.text)["hasAccents"],
                "hasEmoji" to summarize(run.text)["hasEmoji"],
                "hasReplacementChar" to run.text.contains('\uFFFD'),
                "loneSurrogate" to run.text.hasLoneSurrogate(),
                "perChunkStable" to perChunkStable,
                "terminalChunks" to run.terminalChunks.size
            )
        )

        assertTrue(run.text.isNotBlank())
        assertTrue("Accented UTF-8 output must not contain U+FFFD or broken surrogates", run.text.isUtf8Stable())
        assertTrue("Each streamed chunk must decode as valid UTF-8", perChunkStable)
        assertEquals(1, run.terminalChunks.size)

        // Verify that the decoded stream can be re-encoded without loss.
        val reencoded = String(run.text.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(run.text, reencoded)
        NeuralLabReport.record(
            context,
            "utf8_reencode_roundtrip",
            if (reencoded == run.text) "PASS" else "FAIL",
            mapOf("chars" to run.text.length)
        )
    }

    /**
     * Kept last on purpose: feeding a structurally truncated GGUF to llama.cpp may crash
     * the process on some builds. Earlier scenarios already flushed their results.
     */
    @Test
    fun test12_truncated_model_fails_in_native_and_recovers() = runBlocking {
        requireNative("truncated_model_native")
        val model = requireModel("truncated_model_native")

        val truncated = File(context.cacheDir, "lab-truncated-${System.nanoTime()}.gguf")
        try {
            model.inputStream().use { input ->
                truncated.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0
                    while (copied < buffer.size) {
                        val read = input.read(buffer, 0, buffer.size - copied)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                }
            }

            NeuralLabReport.record(
                context,
                "truncated_model_native",
                "INFO",
                mapOf("truncatedBytes" to truncated.length(), "engineState" to engine.state.name)
            )

            val result = withTimeout(180_000) { engine.loadModel(truncated, threads = 4) }
            val failure = result.exceptionOrNull()

            NeuralLabReport.record(
                context,
                "truncated_model_native",
                if (result.isFailure) "PASS" else "FAIL",
                mapOf(
                    "engineState" to engine.state.name,
                    "nativeState" to nativeStateName(),
                    "isModelLoaded" to engine.isModelLoaded()
                ),
                failure?.let { "${it.javaClass.simpleName}: ${it.message}" }
            )

            assertTrue("A truncated model must fail in native code, not crash", result.isFailure)
            assertNotNull(failure)
            assertFalse(engine.isModelLoaded())

            withTimeout(120_000) { engine.unloadModel() }
            loadReady(model)
            assertEquals(EngineState.READY, engine.state)
        } finally {
            truncated.delete()
        }
    }
}
