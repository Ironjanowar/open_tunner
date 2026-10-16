package com.cuwano.afinador.tuner

import com.cuwano.afinador.dsp.SensitivityProfile
import com.cuwano.afinador.dsp.TunerReading
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the processing boundary the session drives: every processor exposes the
 * sensitivity profile so it can be changed without rebuilding the capture chain.
 *
 * The wrapper's engine is private, so forwarding is asserted through the
 * signature and the adapter staying functional rather than by reaching inside.
 */
class TunerProcessorTest {

    @Test
    fun `the engine adapter takes an explicit profile and accepts later changes`() {
        val processor = TunerEngineProcessor(48_000, SensitivityProfile.MUY_SUAVE)

        // Changing sensitivity must be possible on a live processor.
        processor.setProfile(SensitivityProfile.MUY_ALTA)
        processor.setProfile(SensitivityProfile.NORMAL)

        val readings = mutableListOf<TunerReading?>()
        processor.accept(FloatArray(4096), 4096, readings::add)
        processor.reset()

        assertTrue(
            "a silent frame must never fabricate a reading",
            readings.all { it == null },
        )
    }
}
