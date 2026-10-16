package com.cuwano.afinador.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device characterization test for the DSP chain.
 *
 * The DSP is already covered by host unit tests; this test exists to prove the same
 * production code produces correct pitches on the device runtime (ART, device FPU),
 * which host JVM results do not establish. It therefore runs on the emulator/device
 * rather than the JVM and is not part of a RED/GREEN cycle.
 */
@RunWith(AndroidJUnit4::class)
class TunerEngineDeviceTest {

    @Test
    fun engine_detectsA440_onTheDeviceRuntime() {
        val sampleRate = 48_000
        val engine = TunerEngine(
            sampleRate = sampleRate,
            frameSize = FRAME_SIZE,
            hopSize = HOP_SIZE,
            minFrequencyHz = 40.0,
            maxFrequencyHz = 1500.0,
        )
        val readings = mutableListOf<com.cuwano.afinador.dsp.TunerReading?>()
        val samples = FloatArray(sampleRate / 2) { index ->
            (0.7 * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
        }

        var offset = 0
        while (offset < samples.size) {
            val count = minOf(HOP_SIZE, samples.size - offset)
            val capture = FloatArray(count)
            samples.copyInto(capture, endIndex = offset + count, startIndex = offset)
            engine.accept(capture, count, readings::add)
            offset += count
        }

        val acquired = readings.filterNotNull()
        assertTrue("the device runtime produced no reading", acquired.isNotEmpty())
        val last = acquired.last()
        assertTrue(
            "A4 was off by ${centsError(440.0, last.frequencyHz)} cents on device",
            abs(centsError(440.0, last.frequencyHz)) <= 5.0,
        )
        assertEquals("A", last.noteName)
        assertEquals(4, last.octave)
        assertTrue(last.isTuned)
    }

    @Test
    fun engine_staysSilentForSilence_onTheDeviceRuntime() {
        val sampleRate = 48_000
        val engine = TunerEngine(sampleRate = sampleRate, frameSize = FRAME_SIZE, hopSize = HOP_SIZE)
        val readings = mutableListOf<com.cuwano.afinador.dsp.TunerReading?>()

        engine.accept(FloatArray(sampleRate / 4), sampleRate / 4, readings::add)

        assertTrue(readings.isNotEmpty())
        assertTrue("silence produced ${readings.count { it != null }} readings", readings.all { it == null })
    }

    private fun centsError(expectedHz: Double, actualHz: Double): Double =
        1200.0 * ln(actualHz / expectedHz) / ln(2.0)

    private companion object {
        const val FRAME_SIZE = 4096
        const val HOP_SIZE = 1024
    }
}
