package com.cuwano.afinador.tuner

import com.cuwano.afinador.dsp.TunerReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerUiStateTest {
    @Test
    fun `permission needed is a distinct state`() {
        assertSame(
            TunerUiState.PermissionNeeded,
            TunerUiStateReducer.permissionNeeded(permanentlyDenied = false),
        )
    }

    @Test
    fun `permanently denied permission is not reduced to a new request`() {
        assertSame(
            TunerUiState.PermissionDeniedPermanently,
            TunerUiStateReducer.permissionNeeded(permanentlyDenied = true),
        )
    }

    @Test
    fun `listening without a reading becomes silence`() {
        assertSame(TunerUiState.ListeningSilence, TunerUiStateReducer.listening(reading = null))
    }

    @Test
    fun `reading preserves measurements and formats user facing values`() {
        val state = TunerUiStateReducer.listening(
            reading(
                noteName = "C♯",
                octave = 4,
                cents = 12.34,
                frequencyHz = 277.1826,
                confidence = 0.923,
                isTuned = false,
            ),
        ) as TunerUiState.Reading

        assertEquals("C♯4", state.noteWithOctave)
        assertEquals("+12.3 cents", state.centsText)
        assertEquals("277.18 Hz", state.frequencyText)
        assertEquals("92 %", state.confidenceText)
        assertEquals(12.34, state.cents, 0.0)
        assertEquals(277.1826, state.frequencyHz, 0.0)
        assertEquals(0.923, state.confidence, 0.0)
        assertFalse(state.isTuned)
    }

    @Test
    fun `negative cents always include their sign`() {
        val state = TunerUiStateReducer.listening(
            reading(cents = -7.04, isTuned = true),
        ) as TunerUiState.Reading

        assertEquals("-7.0 cents", state.centsText)
        assertTrue(state.isTuned)
    }

    @Test
    fun `audio failure preserves the diagnostic message`() {
        assertEquals(
            TunerUiState.AudioError("No se pudo iniciar el micrófono"),
            TunerUiStateReducer.audioError("No se pudo iniciar el micrófono"),
        )
    }

    private fun reading(
        noteName: String = "A",
        octave: Int = 4,
        frequencyHz: Double = 440.0,
        cents: Double = 0.0,
        confidence: Double = 0.99,
        isTuned: Boolean = true,
    ) = TunerReading(
        noteName = noteName,
        octave = octave,
        frequencyHz = frequencyHz,
        cents = cents,
        confidence = confidence,
        isTuned = isTuned,
        rms = 0.2,
        clipped = false,
    )
}