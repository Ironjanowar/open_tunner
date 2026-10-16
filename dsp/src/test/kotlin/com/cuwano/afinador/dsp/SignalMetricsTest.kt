package com.cuwano.afinador.dsp

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalMetricsTest {
    @Test
    fun `silence has zero DC and RMS without clipping`() {
        val input = FloatArray(8)
        val dcRemoved = FloatArray(input.size) { Float.NaN }

        val measurement = SignalMetrics.analyze(input, dcRemoved)

        assertEquals(0.0, measurement.dcOffset, 1e-12)
        assertEquals(0.0, measurement.rms, 1e-12)
        assertFalse(measurement.clipped)
        assertContentEquals(FloatArray(input.size), dcRemoved)
    }

    @Test
    fun `removes a constant DC component before calculating RMS`() {
        val input = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)
        val original = input.copyOf()
        val dcRemoved = FloatArray(input.size)

        val measurement = SignalMetrics.analyze(input, dcRemoved)

        assertEquals(0.25, measurement.dcOffset, 1e-12)
        assertEquals(0.0, measurement.rms, 1e-12)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), dcRemoved)
        assertContentEquals(original, input, "analysis must not mutate captured samples")
    }

    @Test
    fun `RMS describes the DC-removed waveform`() {
        val input = floatArrayOf(0.75f, 0.25f, -0.25f, 0.25f)
        val dcRemoved = FloatArray(input.size)

        val measurement = SignalMetrics.analyze(input, dcRemoved)

        assertEquals(0.25, measurement.dcOffset, 1e-12)
        assertContentEquals(floatArrayOf(0.5f, 0f, -0.5f, 0f), dcRemoved)
        assertEquals(sqrt(0.125), measurement.rms, 1e-12)
        assertFalse(measurement.clipped)
    }

    @Test
    fun `detects clipping at either polarity`() {
        listOf(
            floatArrayOf(0.99f, 0f),
            floatArrayOf(-0.99f, 0f),
        ).forEach { input ->
            val measurement = SignalMetrics.analyze(
                samples = input,
                dcRemoved = FloatArray(input.size),
                clippingThreshold = 0.99f,
            )

            assertTrue(measurement.clipped, "expected clipping for ${input.contentToString()}")
        }
    }

    @Test
    fun `does not report values below the clipping threshold`() {
        val input = floatArrayOf(0.989f, -0.989f, 0f)

        val measurement = SignalMetrics.analyze(
            samples = input,
            dcRemoved = FloatArray(input.size),
            clippingThreshold = 0.99f,
        )

        assertFalse(measurement.clipped)
    }
}
