package com.cuwano.afinador.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Radix2RealFftTest {
    @Test
    fun `4096 sample spectrum exposes DC through Nyquist bins`() {
        val fft = Radix2RealFft(size = 4096)

        assertEquals(2049, fft.spectrumSize)
    }

    @Test
    fun `magnitude peak lands in the correct frequency bin`() {
        val frameSize = 4096
        val sampleRate = 48_000
        val expectedBin = 85
        val frequencyHz = expectedBin * sampleRate.toDouble() / frameSize
        val samples = SignalFixtures.sine(frequencyHz, sampleRate, frameSize, amplitude = 0.7)
        val original = samples.copyOf()
        val magnitudes = FloatArray(frameSize / 2 + 1)
        val fft = Radix2RealFft(frameSize)

        fft.magnitudeSpectrum(samples, magnitudes)

        val peakBin = magnitudes.indices.maxBy { magnitudes[it] }
        assertEquals(expectedBin, peakBin)
        assertEquals(frequencyHz, fft.binFrequencyHz(peakBin, sampleRate), 1e-12)
        assertTrue(magnitudes[peakBin] > 100f * magnitudes[peakBin - 2])
        assertTrue(samples.contentEquals(original), "FFT must not mutate the input frame")
    }

    @Test
    fun `same FFT and caller-owned output can be reused for subsequent frames`() {
        val frameSize = 4096
        val sampleRate = 44_100
        val fft = Radix2RealFft(frameSize)
        val magnitudes = FloatArray(fft.spectrumSize)

        fft.magnitudeSpectrum(
            SignalFixtures.sine(32 * sampleRate.toDouble() / frameSize, sampleRate, frameSize),
            magnitudes,
        )
        assertEquals(32, magnitudes.indices.maxBy { magnitudes[it] })

        magnitudes.fill(Float.NaN)
        fft.magnitudeSpectrum(
            SignalFixtures.sine(93 * sampleRate.toDouble() / frameSize, sampleRate, frameSize),
            magnitudes,
        )
        assertEquals(93, magnitudes.indices.maxBy { magnitudes[it] })
        assertTrue(magnitudes.all { it.isFinite() && it >= 0f })
    }

    @Test
    fun `rejects non radix-2 sizes and incorrectly sized arrays`() {
        assertFailsWith<IllegalArgumentException> { Radix2RealFft(size = 1000) }

        val fft = Radix2RealFft(size = 4096)
        assertFailsWith<IllegalArgumentException> {
            fft.magnitudeSpectrum(FloatArray(4095), FloatArray(fft.spectrumSize))
        }
        assertFailsWith<IllegalArgumentException> {
            fft.magnitudeSpectrum(FloatArray(4096), FloatArray(fft.spectrumSize - 1))
        }
    }
}
