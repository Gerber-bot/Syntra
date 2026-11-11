package android.llama.cpp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the production LLamaAndroid coroutine control (load / send / unload) against an
 * in-memory [LlamaNativeBridge]. No JNI and no Android native library are executed here.
 */
class LlamaAndroidLifecycleTest {

    private class FakeBridge : LlamaNativeBridge {
        val events = CopyOnWriteArrayList<String>()
        val loopCalls = AtomicInteger(0)
        val cancelCalled = CountDownLatch(1)
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val loopEntered = CountDownLatch(1)
        val releaseLoop = CountDownLatch(1)

        @Volatile var blockOnLoopCall: Int = -1
        @Volatile var blockLoad: Boolean = false
        @Volatile var loopReturnsTokens: Boolean = true
        @Volatile var failFree: String? = null

        override fun isAvailable(): Boolean = true

        override fun benchModel(
            context: Long,
            model: Long,
            batch: Long,
            pp: Int,
            tg: Int,
            pl: Int,
            nr: Int
        ): String = "bench"

        override fun loadModel(path: String): Long {
            events += "load:model"
            if (blockLoad) {
                loadEntered.countDown()
                assertTrue(releaseLoad.await(20, TimeUnit.SECONDS))
            }
            return 11L
        }

        override fun freeModel(model: Long) = free("model")
        override fun newContext(model: Long): Long = 12L
        override fun freeContext(context: Long) = free("context")
        override fun newBatch(nTokens: Int, embd: Int, nSeqMax: Int): Long = 13L
        override fun freeBatch(batch: Long) = free("batch")
        override fun newSampler(): Long = 14L
        override fun freeSampler(sampler: Long) = free("sampler")

        override fun completionInit(
            context: Long,
            batch: Long,
            sampler: Long,
            text: String,
            formatChat: Boolean,
            nLen: Int,
            genId: Long
        ): Int {
            events += "init:$genId"
            return 3
        }

        override fun completionLoop(
            context: Long,
            batch: Long,
            sampler: Long,
            nLen: Int,
            nCur: Int,
            genId: Long
        ): String? {
            val call = loopCalls.incrementAndGet()
            if (call == blockOnLoopCall) {
                loopEntered.countDown()
                assertTrue(releaseLoop.await(20, TimeUnit.SECONDS))
            }
            return if (loopReturnsTokens) "x" else null
        }

        override fun kvCacheClear(context: Long) {
            events += "kv:$context"
        }

        override fun cancelGeneration(genId: Long) {
            events += "cancel:$genId"
            cancelCalled.countDown()
        }

        fun freeNames(): List<String> = events.filter { it.startsWith("free:") }.map { it.removePrefix("free:") }

        private fun free(name: String) {
            if (failFree == name) {
                events += "fail:$name"
                throw IllegalStateException("controlled free failure: $name")
            }
            events += "free:$name"
        }
    }

    private fun newLlama(bridge: FakeBridge): LLamaAndroid {
        val llama = LLamaAndroid()
        llama.nativeBridge = bridge
        return llama
    }

    private suspend fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(10)
        }
    }

    private fun assertIdle(llama: LLamaAndroid) {
        assertEquals(LLamaAndroid.Companion.State.Idle, llama.getCurrentState())
    }

    private fun assertUnloading(llama: LLamaAndroid) {
        val state = llama.getCurrentState()
        assertTrue("expected Unloading but was $state", state is LLamaAndroid.Companion.State.Unloading)
    }

    private fun assertKvBeforeFree(bridge: FakeBridge) {
        val kvIndex = bridge.events.indexOfFirst { it.startsWith("kv:") }
        val firstFree = bridge.events.indexOfFirst { it.startsWith("free:") }
        assertTrue("missing kv cache clear", kvIndex >= 0)
        assertTrue("missing resource free", firstFree >= 0)
        assertTrue("kv cache must be cleared before freeing resources: ${bridge.events}", kvIndex < firstFree)
    }

    @Test
    fun `unload waits for a generation suspended in emit and frees once after kv clear`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            llama.load("audit-model")

            val firstChunk = CompletableDeferred<Unit>()
            val releaseConsumer = CompletableDeferred<Unit>()
            val generation = launch(Dispatchers.Default) {
                llama.send("prompt").buffer(0, BufferOverflow.SUSPEND).collect {
                    firstChunk.complete(Unit)
                    releaseConsumer.await()
                }
            }
            firstChunk.await()
            awaitCondition { bridge.loopCalls.get() >= 2 }

            val unload = launch(Dispatchers.Default) { llama.unload() }
            unload.join()
            releaseConsumer.complete(Unit)
            generation.join()

            assertEquals(listOf("sampler", "batch", "context", "model"), bridge.freeNames())
            assertKvBeforeFree(bridge)
            assertIdle(llama)
        } finally {
            llama.shutdownForTest()
        }
    }

    @Test
    fun `cancelling the unload caller keeps ownership and completes cleanup`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            llama.load("audit-model")
            bridge.blockOnLoopCall = 2

            val firstChunk = CompletableDeferred<Unit>()
            val releaseConsumer = CompletableDeferred<Unit>()
            val generation = launch(Dispatchers.Default) {
                llama.send("prompt").buffer(0, BufferOverflow.SUSPEND).collect {
                    firstChunk.complete(Unit)
                    releaseConsumer.await()
                }
            }
            firstChunk.await()
            assertTrue(bridge.loopEntered.await(5, TimeUnit.SECONDS))

            val unload = launch(Dispatchers.Default) { llama.unload() }
            delay(200)
            unload.cancel()
            unload.join()
            delay(200)

            assertUnloading(llama)
            assertTrue("cancelling the caller must not free resources early", bridge.freeNames().isEmpty())

            bridge.releaseLoop.countDown()
            releaseConsumer.complete(Unit)
            awaitCondition(5_000) { llama.getCurrentState() == LLamaAndroid.Companion.State.Idle }

            assertEquals(listOf("sampler", "batch", "context", "model"), bridge.freeNames())
            assertKvBeforeFree(bridge)
            generation.join()
        } finally {
            bridge.releaseLoop.countDown()
            llama.shutdownForTest()
        }
    }

    @Test
    fun `two concurrent unloads release every resource exactly once`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            llama.load("audit-model")

            val firstChunk = CompletableDeferred<Unit>()
            val releaseConsumer = CompletableDeferred<Unit>()
            val generation = launch(Dispatchers.Default) {
                llama.send("prompt").buffer(0, BufferOverflow.SUSPEND).collect {
                    firstChunk.complete(Unit)
                    releaseConsumer.await()
                }
            }
            firstChunk.await()
            awaitCondition { bridge.loopCalls.get() >= 2 }

            val first = launch(Dispatchers.Default) { llama.unload() }
            val second = launch(Dispatchers.Default) { llama.unload() }
            assertTrue(bridge.cancelCalled.await(5, TimeUnit.SECONDS))

            releaseConsumer.complete(Unit)
            first.join()
            second.join()
            generation.join()

            val freed = bridge.freeNames()
            assertEquals(4, freed.size)
            assertEquals(4, freed.toSet().size)
            assertKvBeforeFree(bridge)
            assertIdle(llama)
        } finally {
            llama.shutdownForTest()
        }
    }

    @Test
    fun `free failure keeps ownership of remaining resources and retry completes unload`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            llama.load("audit-model")
            bridge.failFree = "sampler"

            val failure = runCatching { llama.unload() }.exceptionOrNull()
            assertNotNull("unload must report incomplete cleanup", failure)
            assertUnloading(llama)
            assertEquals(listOf("batch", "context", "model"), bridge.freeNames())

            bridge.failFree = null
            llama.unload()

            assertEquals(listOf("batch", "context", "model", "sampler"), bridge.freeNames())
            assertIdle(llama)
        } finally {
            llama.shutdownForTest()
        }
    }

    @Test
    fun `context free failure never frees the model it still references`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            llama.load("audit-model")
            bridge.failFree = "context"

            assertNotNull(runCatching { llama.unload() }.exceptionOrNull())
            assertUnloading(llama)
            assertEquals(listOf("sampler", "batch"), bridge.freeNames())
            assertFalse("model must not be freed while its context is alive", "model" in bridge.freeNames())

            bridge.failFree = null
            llama.unload()

            assertEquals(listOf("sampler", "batch", "context", "model"), bridge.freeNames())
            assertIdle(llama)
        } finally {
            llama.shutdownForTest()
        }
    }

    @Test
    fun `unload during load waits for the rollback instead of publishing Idle early`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            bridge.blockLoad = true
            val load = launch(Dispatchers.Default) { runCatching { llama.load("audit-model") } }
            assertTrue(bridge.loadEntered.await(5, TimeUnit.SECONDS))

            val unload = launch(Dispatchers.Default) { llama.unload() }
            delay(300)

            assertFalse("unload must wait for the in-progress load rollback", unload.isCompleted)
            assertTrue("nothing may be freed before load rollback", bridge.freeNames().isEmpty())
            assertUnloading(llama)

            bridge.releaseLoad.countDown()
            load.join()
            unload.join()

            assertEquals(listOf("model"), bridge.freeNames())
            assertIdle(llama)
        } finally {
            llama.shutdownForTest()
        }
    }

    @Test
    fun `unloading a generation does not cancel the surrounding chat scope`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            llama.load("audit-model")
            coroutineScope {
                val sibling = launch { delay(10_000) }
                val firstChunk = CompletableDeferred<Unit>()
                val collector = launch(Dispatchers.Default) {
                    llama.send("prompt").buffer(0, BufferOverflow.SUSPEND).collect {
                        firstChunk.complete(Unit)
                        awaitCancellation()
                    }
                }
                firstChunk.await()
                awaitCondition { bridge.loopCalls.get() >= 2 }

                llama.unload()

                assertTrue("unload must not cancel sibling work in the caller scope", sibling.isActive)
                sibling.cancel()
                collector.cancel()
            }
            assertIdle(llama)
        } finally {
            llama.shutdownForTest()
        }
    }

    @Test
    fun `unload does not free resources when the generation outlives the 5000 ms diagnostic timeout`() = runBlocking {
        val bridge = FakeBridge()
        val llama = newLlama(bridge)
        try {
            llama.load("audit-model")
            bridge.blockOnLoopCall = 2

            val firstChunk = CompletableDeferred<Unit>()
            val releaseConsumer = CompletableDeferred<Unit>()
            val generation = launch(Dispatchers.Default) {
                llama.send("prompt").buffer(0, BufferOverflow.SUSPEND).collect {
                    firstChunk.complete(Unit)
                    releaseConsumer.await()
                }
            }
            firstChunk.await()
            assertTrue(bridge.loopEntered.await(5, TimeUnit.SECONDS))

            val unload = launch(Dispatchers.Default) { llama.unload() }
            delay(5_200)

            assertFalse("unload must not complete while the native generation is active", unload.isCompleted)
            assertTrue("no resource may be released by timeout", bridge.freeNames().isEmpty())
            assertUnloading(llama)

            bridge.releaseLoop.countDown()
            releaseConsumer.complete(Unit)
            unload.join()
            generation.join()

            assertEquals(listOf("sampler", "batch", "context", "model"), bridge.freeNames())
            assertKvBeforeFree(bridge)
            assertIdle(llama)
        } finally {
            bridge.releaseLoop.countDown()
            bridge.releaseLoad.countDown()
            llama.shutdownForTest()
        }
    }
}
