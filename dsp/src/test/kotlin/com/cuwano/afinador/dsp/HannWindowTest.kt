package com.cuwano.afinador.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HannWindowTest {
    @Test
    fun `Hann coefficients have zero endpoints and even symmetry`() {
        val samples = FloatArray(9) { 1f }

        HannWindow(samples.size).applyInPlace(samples)

        assertEquals(0f, samples.first(), 1e-7f)
        assertEquals(0f, samples.last(), 1e-7f)
        for (index in samples.indices) {
            assertEquals(samples[index], samples[samples.lastIndex - index], 1e-6f, "index $index")
        }
        assertEquals(1f, samples[samples.size / 2], 1e-6f)
    }

    @Test
    fun `applies the precomputed window in place and can be reused`() {
        val window = HannWindow(size = 5)
        val first = floatArrayOf(2f, 2f, 2f, 2f, 2f)
        val alias = first
        val second = floatArrayOf(4f, 4f, 4f, 4f, 4f)

        window.applyInPlace(first)
        window.applyInPlace(second)

        assertEquals(0f, alias[0], 1e-6f)
        assertEquals(1f, alias[1], 1e-6f)
        assertEquals(2f, alias[2], 1e-6f)
        assertEquals(1f, alias[3], 1e-6f)
        assertEquals(0f, alias[4], 1e-6f)
        assertEquals(0f, second[0], 1e-6f)
        assertEquals(2f, second[1], 1e-6f)
        assertEquals(4f, second[2], 1e-6f)
        assertEquals(2f, second[3], 1e-6f)
        assertEquals(0f, second[4], 1e-6f)
    }

    @Test
    fun `rejects applying a window to a differently sized frame`() {
        val window = HannWindow(size = 8)

        assertFailsWith<IllegalArgumentException> {
            window.applyInPlace(FloatArray(7))
        }
    }
}
