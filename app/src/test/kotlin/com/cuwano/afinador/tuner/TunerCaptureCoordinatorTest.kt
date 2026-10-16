package com.cuwano.afinador.tuner

import com.cuwano.afinador.audio.AudioInput
import com.cuwano.afinador.audio.AudioInputFactory
import com.cuwano.afinador.dsp.TunerReading
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
        override fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit) = Unit

        override fun reset() = Unit
    }

    private companion object {
        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(message, condition())
        }
    }
}
