package com.cuwano.afinador.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED contract for the pure PCM16 conversion utility.
 *
 * Required production surface (package `com.cuwano.afinador.audio`):
 *
 * ```
 * object Pcm16 {
 *     const val FULL_SCALE: Float = 32768f
 *     fun toFloats(samples: ShortArray, count: Int = samples.size): FloatArray
 * }
 * ```
 *
 * Mapping: `sample / FULL_SCALE`, so that the documented full-scale endpoints are
 * `Short.MIN_VALUE` (-32768) -> exactly -1.0, `Short.MAX_VALUE` (32767) -> 0.99996...,
 * and 0 -> 0.0. The result has exactly [count] entries, in input order, and never
 * leaves the normalized [-1, 1] range.
 */
class Pcm16Test {

    @Test
    fun `documented full scale samples map to the normalized endpoints`() {
        val converted = Pcm16.toFloats(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE))

        assertEquals("Short.MIN_VALUE (-32768) must map to exactly -1.0", -1.0f, converted[0], 0f)
        assertEquals("zero must map to 0.0", 0.0f, converted[1], 0f)
        assertEquals(
            "Short.MAX_VALUE (32767) must map to 32767/32768",
            32767f / 32768f,
            converted[2],
            1e-7f,
        )
    }

    @Test
    fun `most negative sample saturates at the normalized minimum`() {
        val converted = Pcm16.toFloats(
            shortArrayOf(Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MAX_VALUE),
        )

        assertTrue(
            "converted samples must never fall outside [-1, 1]",
            converted.all { it >= -1.0f && it <= 1.0f },
        )
        assertEquals("the most negative sample saturates exactly at -1.0", -1.0f, converted[0], 0f)
        assertEquals(-1.0f, converted[1], 0f)
    }

    @Test
    fun `sign and ordering are preserved for intermediate samples`() {
        val converted = Pcm16.toFloats(shortArrayOf(-16384, 0, 16384))

        assertEquals(-0.5f, converted[0], 1e-7f)
        assertEquals(0.0f, converted[1], 0f)
        assertEquals(0.5f, converted[2], 1e-7f)
    }

    @Test
    fun `count converts only the first samples in order`() {
        val samples = shortArrayOf(1000, -2000, 3000, -4000)

        val limited = Pcm16.toFloats(samples, count = 2)

        assertEquals("the result length equals the requested count", 2, limited.size)
        assertEquals(1000f / 32768f, limited[0], 1e-7f)
        assertEquals(-2000f / 32768f, limited[1], 1e-7f)
    }

    @Test
    fun `a zero count yields an empty result`() {
        assertTrue(Pcm16.toFloats(shortArrayOf(1, 2, 3), count = 0).isEmpty())
    }

    @Test
    fun `a full count converts every sample`() {
        assertEquals(4, Pcm16.toFloats(shortArrayOf(1, 2, 3, 4), count = 4).size)
    }

    @Test
    fun `a count outside the sample range is rejected`() {
        val samples = shortArrayOf(1, 2, 3)

        assertThrows(IllegalArgumentException::class.java) {
            Pcm16.toFloats(samples, count = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pcm16.toFloats(samples, count = -1)
        }
    }
}
