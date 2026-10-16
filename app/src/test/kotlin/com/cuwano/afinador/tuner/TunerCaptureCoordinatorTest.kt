package com.cuwano.afinador.tuner

import com.cuwano.afinador.audio.AudioInput
import com.cuwano.afinador.audio.AudioInputFactory
import com.cuwano.afinador.dsp.SensitivityProfile
import com.cuwano.afinador.dsp.TunerReading
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerCaptureCoordinatorTest {

    @Test
    fun `capture requires both the permission and a resumed lifecycle`() {
        assertTrue(TunerPermissionPolicy.shouldCapture(permissionGranted = true, lifecycleResumed = true))
        assertFalse(TunerPermissionPolicy.shouldCapture(permissionGranted = true, lifecycleResumed = false))
        assertFalse(TunerPermissionPolicy.shouldCapture(permissionGranted = false, lifecycleResumed = true))
        assertFalse(TunerPermissionPolicy.shouldCapture(permissionGranted = false, lifecycleResumed = false))
    }

    @Test
    fun `a denial with no rationale after the dialog counts as permanent`() {
        assertTrue(
            TunerPermissionPolicy.isPermanentlyDenied(
                granted = false,
                shouldShowRationale = false,
                hasRequested = true,
            ),
        )
    }

    @Test
    fun `a first time denial keeps the request action available`() {
        assertFalse(
            TunerPermissionPolicy.isPermanentlyDenied(
                granted = false,
                shouldShowRationale = true,
                hasRequested = true,
            ),
        )
    }

    @Test
    fun `a never requested permission is not treated as permanently denied`() {
        assertFalse(
            TunerPermissionPolicy.isPermanentlyDenied(
                granted = false,
                shouldShowRationale = false,
                hasRequested = false,
            ),
        )
    }

    @Test
    fun `a granted permission is never treated as permanently denied`() {
        assertFalse(
            TunerPermissionPolicy.isPermanentlyDenied(
                granted = true,
                shouldShowRationale = false,
                hasRequested = true,
            ),
        )
    }

    @Test
    fun `reconcile starts capture while resumed and stops it on pause`() {
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { SilentAudioInput() },
            processorFactory = { SilentProcessor() },
        )
        val coordinator = TunerCaptureCoordinator(session)
        try {
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("session did not start listening") {
                session.state is TunerSessionState.Listening
            }

            coordinator.reconcile(permissionGranted = true, lifecycleResumed = false)
            assertTrue(
                "capture must stop when the lifecycle leaves RESUMED",
                session.state is TunerSessionState.Stopped,
            )
        } finally {
            session.stop()
        }
    }

    @Test
    fun `ui state maps a denial to a request or a settings prompt`() {
        val coordinator = TunerCaptureCoordinator(neverStartedSession())

        assertEquals(
            TunerUiState.PermissionNeeded,
            coordinator.uiState(permissionGranted = false, permanentlyDenied = false),
        )
        assertEquals(
            TunerUiState.PermissionDeniedPermanently,
            coordinator.uiState(permissionGranted = false, permanentlyDenied = true),
        )
    }

    @Test
    fun `ui state falls back to silence while granted capture is stopped`() {
        val coordinator = TunerCaptureCoordinator(neverStartedSession())

        assertEquals(
            TunerUiState.ListeningSilence,
            coordinator.uiState(permissionGranted = true, permanentlyDenied = false),
        )
    }

    @Test
    fun `ui state holds the last note as a stale reading once capture has stopped`() {
        val published = TunerReading(
            noteName = "A",
            octave = 4,
            frequencyHz = 440.0,
            cents = 0.0,
            confidence = 0.9,
            isTuned = true,
            isLive = true,
            rms = 0.25,
            clipped = false,
        )
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { StreamingAudioInput() },
            processorFactory = { PublishingProcessor(published) },
            captureBufferSize = 8,
        )
        val coordinator = TunerCaptureCoordinator(session)
        try {
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("the note was never displayed while listening") {
                session.latestReading == published
            }

            coordinator.reconcile(permissionGranted = true, lifecycleResumed = false)
            assertTrue(
                "capture must stop when the lifecycle leaves RESUMED",
                session.state is TunerSessionState.Stopped,
            )

            val state = coordinator.uiState(permissionGranted = true, permanentlyDenied = false)
            val reading = assertIsReading(state)
            assertFalse("a stopped session must hold its note as stale", reading.isLive)
            assertTrue(reading.isStale)
            assertEquals("Última nota", reading.statusText)
            assertEquals("A", reading.noteName)
            assertEquals(4, reading.octave)
            assertEquals(440.0, reading.frequencyHz, 0.0)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a restart whose first frames publish null holds the previous note as stale`() {
        val published = TunerReading(
            noteName = "A",
            octave = 4,
            frequencyHz = 440.0,
            cents = 0.0,
            confidence = 0.9,
            isTuned = true,
            isLive = true,
            rms = 0.25,
            clipped = false,
        )
        // Leaving and returning rebuilds the processor with a fresh engine, whose
        // first frames publish null. The note already on screen must survive that.
        val firstProcessor = ScriptedProcessor { published }
        val rebuiltProcessor = ScriptedProcessor { null }
        var created = 0
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { StreamingAudioInput() },
            processorFactory = { if (created++ == 0) firstProcessor else rebuiltProcessor },
            captureBufferSize = 8,
        )
        val coordinator = TunerCaptureCoordinator(session)
        try {
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("the note was never displayed while listening") {
                session.latestReading == published
            }

            coordinator.reconcile(permissionGranted = true, lifecycleResumed = false)
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("the rebuilt session did not resume listening") {
                session.state is TunerSessionState.Listening
            }
            awaitCondition("the rebuilt processor did not process its null frames") {
                rebuiltProcessor.acceptCalls.get() >= 2
            }

            val state = coordinator.uiState(permissionGranted = true, permanentlyDenied = false)
            val reading = assertIsReading(state)
            assertEquals("A", reading.noteName)
            assertEquals(4, reading.octave)
            assertEquals(440.0, reading.frequencyHz, 0.0)
            assertFalse("the rebuilt session must not present the held note as live", reading.isLive)
            assertTrue(reading.isStale)
            assertEquals("Última nota", reading.statusText)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a restart before any frame is accepted is not shown as a live tuned note`() {
        val published = tunedNote()
        // The first capture publishes a tuned note. The rebuilt (fresh) capture gets a
        // processor that never republishes anything and an input that blocks in read(),
        // so no frame reaches the processor after the restart. Until a genuinely new
        // frame arrives the coordinator must present the previous note as stale, not as
        // a live tuned note showing "Afinado".
        val firstProcessor = PublishingProcessor(published)
        val rebuiltProcessor = SilentProcessor()
        val streaming = StreamingAudioInput()
        val blocking = BlockingAudioInput()
        var inputsCreated = 0
        var processorsCreated = 0
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { if (inputsCreated++ == 0) streaming else blocking },
            processorFactory = { if (processorsCreated++ == 0) firstProcessor else rebuiltProcessor },
            captureBufferSize = 8,
        )
        val coordinator = TunerCaptureCoordinator(session)
        try {
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("the tuned note was never displayed while listening") {
                session.latestReading?.isLive == true && session.latestReading?.isTuned == true
            }

            coordinator.reconcile(permissionGranted = true, lifecycleResumed = false)
            assertTrue(
                "capture must stop when the lifecycle leaves RESUMED",
                session.state is TunerSessionState.Stopped,
            )
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("the rebuilt session did not resume listening") {
                session.state is TunerSessionState.Listening
            }
            assertEquals(
                "no frame may reach the rebuilt processor before the state is read",
                0,
                rebuiltProcessor.acceptCalls.get(),
            )

            val reading = assertIsReading(coordinator.uiState(permissionGranted = true, permanentlyDenied = false))
            assertFalse("a restart must not show the previous note as live before a new frame", reading.isLive)
            assertFalse("a restart must not show the previous note as tuned before a new frame", reading.isTuned)
            assertTrue(reading.isStale)
            assertEquals("Última nota", reading.statusText)
            assertEquals("A", reading.noteName)
            assertEquals(4, reading.octave)
            assertEquals(440.0, reading.frequencyHz, 0.0)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a stopped session reports its held note as neither live nor tuned`() {
        val published = tunedNote()
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { StreamingAudioInput() },
            processorFactory = { PublishingProcessor(published) },
            captureBufferSize = 8,
        )
        val coordinator = TunerCaptureCoordinator(session)
        try {
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("the tuned note was never displayed while listening") {
                session.latestReading == published
            }

            coordinator.reconcile(permissionGranted = true, lifecycleResumed = false)
            assertTrue(
                "capture must stop when the lifecycle leaves RESUMED",
                session.state is TunerSessionState.Stopped,
            )

            val reading = assertIsReading(coordinator.uiState(permissionGranted = true, permanentlyDenied = false))
            assertFalse("a stopped session must not leave its note live", reading.isLive)
            assertFalse(
                "a stopped session must not leave its note tuned: the cents readout and needle stay green",
                reading.isTuned,
            )
            assertTrue(reading.isStale)
            assertEquals("Última nota", reading.statusText)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a listening session that never detected anything stays silent`() {
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { SilentAudioInput() },
            processorFactory = { SilentProcessor() },
        )
        val coordinator = TunerCaptureCoordinator(session)
        try {
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)
            awaitCondition("session did not start listening") {
                session.state is TunerSessionState.Listening
            }

            assertEquals(
                "a session that has never detected a note must show silence, not a reading",
                TunerUiState.ListeningSilence,
                coordinator.uiState(permissionGranted = true, permanentlyDenied = false),
            )
        } finally {
            session.stop()
        }
    }

    @Test
    fun `ui state surfaces an audio failure with its diagnostic`() {
        val session = TunerSession(
            audioInputFactory = AudioInputFactory { throw IllegalStateException("no hay micrófono") },
            processorFactory = { error("processor must not be created when capture cannot start") },
        )
        val coordinator = TunerCaptureCoordinator(session)
        try {
            coordinator.reconcile(permissionGranted = true, lifecycleResumed = true)

            val state = coordinator.uiState(permissionGranted = true, permanentlyDenied = false)
            assertTrue("expected an audio error, got $state", state is TunerUiState.AudioError)
            assertEquals("no hay micrófono", (state as TunerUiState.AudioError).message)
        } finally {
            session.stop()
        }
    }

    private fun neverStartedSession(): TunerSession = TunerSession(
        audioInputFactory = AudioInputFactory { SilentAudioInput() },
        processorFactory = { SilentProcessor() },
    )

    private class SilentAudioInput : AudioInput {
        override val sampleRate: Int = 48_000

        override fun start() = Unit

        override fun read(buffer: FloatArray): Int {
            Thread.sleep(1)
            return 0
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private class SilentProcessor : TunerProcessor {
        val acceptCalls = AtomicInteger()

        override fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit) {
            acceptCalls.incrementAndGet()
        }

        override fun reset() = Unit

        override fun setProfile(profile: SensitivityProfile) = Unit
    }

    private class StreamingAudioInput : AudioInput {
        override val sampleRate: Int = 48_000

        override fun start() = Unit

        override fun read(buffer: FloatArray): Int {
            Thread.sleep(1)
            buffer[0] = 0.3f
            return 1
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    /**
     * Blocks inside [read] until [stop] releases it, so no frame is ever handed to the
     * processor. Used to observe the coordinator state between a restart and the first
     * accepted frame.
     */
    private class BlockingAudioInput : AudioInput {
        override val sampleRate: Int = 48_000
        private val released = CountDownLatch(1)

        override fun start() = Unit

        override fun read(buffer: FloatArray): Int {
            released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return -1
        }

        override fun stop() {
            released.countDown()
        }

        override fun release() = Unit
    }

    private class PublishingProcessor(private val reading: TunerReading) : TunerProcessor {
        override fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit) {
            onReading(reading)
        }

        override fun reset() = Unit

        override fun setProfile(profile: SensitivityProfile) = Unit
    }

    /** Publishes whatever the script returns for the nth accepted frame, `null` included. */
    private class ScriptedProcessor(
        private val script: (Int) -> TunerReading?,
    ) : TunerProcessor {
        val acceptCalls = AtomicInteger()

        override fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit) {
            onReading(script(acceptCalls.getAndIncrement()))
        }

        override fun reset() = Unit

        override fun setProfile(profile: SensitivityProfile) = Unit
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L

        fun assertIsReading(state: TunerUiState): TunerUiState.Reading {
            assertTrue("expected a reading state, got $state", state is TunerUiState.Reading)
            return state as TunerUiState.Reading
        }

        /** A live, tuned A4, the note a restart must stop claiming is currently heard. */
        fun tunedNote() = TunerReading(
            noteName = "A",
            octave = 4,
            frequencyHz = 440.0,
            cents = 0.0,
            confidence = 0.9,
            isTuned = true,
            isLive = true,
            rms = 0.25,
            clipped = false,
        )

        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(message, condition())
        }
    }
}
