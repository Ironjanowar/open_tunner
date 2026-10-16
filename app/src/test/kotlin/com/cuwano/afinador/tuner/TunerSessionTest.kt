package com.cuwano.afinador.tuner

import com.cuwano.afinador.audio.AudioInput
import com.cuwano.afinador.audio.AudioInputFactory
import com.cuwano.afinador.dsp.TunerReading
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerSessionTest {
    @Test
    fun `falls back from 48000 to 44100 and builds processor for actual input rate`() {
        val attemptedRates = mutableListOf<Int>()
        val failedInput = FailingStartAudioInput(sampleRate = 48_000)
        val fallbackInput = BlockingAudioInput(sampleRate = 44_100)
        val processorRates = mutableListOf<Int>()
        val processor = RecordingProcessor()
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { requestedRate ->
                attemptedRates += requestedRate
                if (requestedRate == 48_000) failedInput else fallbackInput
            },
            processorFactory = { actualRate ->
                processorRates += actualRate
                processor
            },
        )

        try {
            session.start()
            assertTrue("fallback input did not start", fallbackInput.awaitStarted())
            awaitCondition("session did not enter listening state") {
                session.state is TunerSessionState.Listening
            }

            assertEquals(listOf(48_000, 44_100), attemptedRates)
            assertEquals(listOf(44_100), processorRates)
            assertEquals("failed input must be released before fallback", 1, failedInput.releaseCalls.get())
            assertIs<TunerSessionState.Listening>(session.state)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `feeds only valid samples and publishes the latest processor reading`() {
        val input = BlockingAudioInput(sampleRate = 48_000)
        val expectedReading = reading(frequencyHz = 440.0)
        val processor = RecordingProcessor(readingToPublish = expectedReading)
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { input },
            processorFactory = { processor },
            captureBufferSize = 8,
        )

        try {
            session.start()
            assertTrue("audio input did not start", input.awaitStarted())
            input.enqueue(samples = floatArrayOf(0.1f, 0.2f, 99f, 100f), validCount = 2)
            assertTrue("processor did not receive captured samples", processor.awaitAccepted())
            awaitCondition("latest reading was not published") { session.latestReading == expectedReading }

            assertEquals(listOf(2), processor.counts)
            assertEquals(listOf(0.1f, 0.2f), processor.samplePrefixes.single())
            assertEquals(expectedReading, session.latestReading)
            assertIs<TunerSessionState.Listening>(session.state)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `stop is idempotent joins the worker resets state and does not process a late read`() {
        val input = LateReadAfterStopAudioInput(sampleRate = 48_000)
        val processor = RecordingProcessor()
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { input },
            processorFactory = { processor },
        )

        session.start()
        assertTrue("worker never entered audio read", input.awaitReadEntered())

        session.stop()
        assertTrue("stop returned before read exited", input.readExited.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("a read completed after stop must not be processed", 0, processor.acceptCalls.get())
        assertEquals(1, processor.resetCalls.get())
        assertEquals(1, input.stopCalls.get())
        assertEquals(1, input.releaseCalls.get())
        assertNull(session.latestReading)
        assertIs<TunerSessionState.Stopped>(session.state)

        session.stop()
        assertEquals("a second stop must have no lifecycle side effects", 1, processor.resetCalls.get())
        assertEquals("a second stop must not stop input again", 1, input.stopCalls.get())
        assertEquals("a second stop must not release input again", 1, input.releaseCalls.get())
        assertEquals("processing must remain stopped", 0, processor.acceptCalls.get())
    }

    @Test
    fun `negative read result publishes a user visible error and releases input`() {
        val input = ErrorAudioInput(sampleRate = 48_000, readResult = -3)
        val processor = RecordingProcessor()
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { input },
            processorFactory = { processor },
        )

        try {
            session.start()
            assertTrue("input was not released after read error", input.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            awaitCondition("read error was not published") { session.state is TunerSessionState.Error }

            val error = assertIs<TunerSessionState.Error>(session.state)
            assertTrue("error state needs a user-visible message", error.message.isNotBlank())
            assertEquals(1, input.releaseCalls.get())
            assertEquals(0, processor.acceptCalls.get())
            assertNull(session.latestReading)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `failure at both preferred sample rates publishes an error without a worker`() {
        val attemptedRates = mutableListOf<Int>()
        val inputs = mutableListOf<FailingStartAudioInput>()
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { requestedRate ->
                attemptedRates += requestedRate
                FailingStartAudioInput(requestedRate).also(inputs::add)
            },
            processorFactory = { error("processor must not be created when capture cannot start") },
        )

        session.start()
        awaitCondition("startup failure was not published") { session.state is TunerSessionState.Error }

        assertEquals(listOf(48_000, 44_100), attemptedRates)
        assertTrue("every failed input must be released", inputs.all { it.releaseCalls.get() == 1 })
        val error = assertIs<TunerSessionState.Error>(session.state)
        assertTrue("startup failure needs a user-visible message", error.message.isNotBlank())
        session.stop()
    }

    private class RecordingProcessor(
        private val readingToPublish: TunerReading? = null,
    ) : TunerProcessor {
        val acceptCalls = AtomicInteger()
        val resetCalls = AtomicInteger()
        val counts = mutableListOf<Int>()
        val samplePrefixes = mutableListOf<List<Float>>()
        private val accepted = CountDownLatch(1)

        override fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit) {
            acceptCalls.incrementAndGet()
            synchronized(this) {
                counts += count
                samplePrefixes += samples.take(count)
            }
            onReading(readingToPublish)
            accepted.countDown()
        }

        override fun reset() {
            resetCalls.incrementAndGet()
        }

        fun awaitAccepted(): Boolean = accepted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private class BlockingAudioInput(
        override val sampleRate: Int,
    ) : AudioInput {
        private val events = LinkedBlockingQueue<ReadEvent>()
        private val started = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        override fun start() {
            started.countDown()
        }

        override fun read(buffer: FloatArray): Int {
            return when (val event = events.take()) {
                is ReadEvent.Samples -> {
                    event.samples.copyInto(buffer)
                    event.validCount
                }
                ReadEvent.End -> -1
            }
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            events.offer(ReadEvent.End)
        }

        override fun release() {
            releaseCalls.incrementAndGet()
        }

        fun enqueue(samples: FloatArray, validCount: Int) {
            require(samples.size <= 8)
            require(validCount in 0..samples.size)
            events.put(ReadEvent.Samples(samples, validCount))
        }

        fun awaitStarted(): Boolean = started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private class LateReadAfterStopAudioInput(
        override val sampleRate: Int,
    ) : AudioInput {
        private val readEntered = CountDownLatch(1)
        private val unblockRead = CountDownLatch(1)
        val readExited = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        override fun start() = Unit

        override fun read(buffer: FloatArray): Int {
            readEntered.countDown()
            assertTrue("test input was not stopped", unblockRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            buffer[0] = 0.75f
            readExited.countDown()
            return 1
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            unblockRead.countDown()
        }

        override fun release() {
            releaseCalls.incrementAndGet()
        }

        fun awaitReadEntered(): Boolean = readEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private class ErrorAudioInput(
        override val sampleRate: Int,
        private val readResult: Int,
    ) : AudioInput {
        val released = CountDownLatch(1)
        val releaseCalls = AtomicInteger()

        override fun start() = Unit

        override fun read(buffer: FloatArray): Int = readResult

        override fun stop() = Unit

        override fun release() {
            releaseCalls.incrementAndGet()
            released.countDown()
        }
    }

    private class FailingStartAudioInput(
        override val sampleRate: Int,
    ) : AudioInput {
        val releaseCalls = AtomicInteger()

        override fun start(): Unit = throw IllegalStateException("unsupported sample rate $sampleRate")

        override fun read(buffer: FloatArray): Int = error("read must not be called")

        override fun stop() = Unit

        override fun release() {
            releaseCalls.incrementAndGet()
        }
    }

    private sealed interface ReadEvent {
        data class Samples(val samples: FloatArray, val validCount: Int) : ReadEvent
        data object End : ReadEvent
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L

        fun reading(frequencyHz: Double) = TunerReading(
            noteName = "A",
            octave = 4,
            frequencyHz = frequencyHz,
            cents = 0.0,
            confidence = 0.9,
            isTuned = true,
            rms = 0.25,
            clipped = false,
        )

        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (!condition() && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(message, condition())
        }

        inline fun <reified T> assertIs(actual: Any?): T {
            assertTrue("expected ${T::class.java.name}, got ${actual?.javaClass?.name}", actual is T)
            return actual as T
        }
    }
}
