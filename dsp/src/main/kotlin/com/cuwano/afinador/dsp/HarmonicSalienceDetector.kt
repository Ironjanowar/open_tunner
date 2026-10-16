package com.cuwano.afinador.dsp

import kotlin.math.pow
import kotlin.math.sqrt

/** A spectrum-derived fundamental candidate for a later confidence/tracking stage. */
data class PitchProposal(
    val frequencyHz: Double,
    val salience: Double,
)

class HarmonicSalienceDetector(
    private val frameSize: Int,
    private val minFrequencyHz: Double,
    private val maxFrequencyHz: Double,
) {
    private val window = HannWindow(frameSize)
    private val fft = Radix2RealFft(frameSize)
    private val windowed = FloatArray(frameSize)
    private val magnitudes = FloatArray(fft.spectrumSize)

    init {
        require(minFrequencyHz.isFinite() && minFrequencyHz > 0.0) {
            "minFrequencyHz must be finite and positive"
        }
        require(maxFrequencyHz.isFinite() && maxFrequencyHz > minFrequencyHz) {
            "maxFrequencyHz must be finite and greater than minFrequencyHz"
        }
    }

    fun propose(samples: FloatArray, sampleRate: Int): PitchProposal? {
        require(samples.size == frameSize) { "samples must match frameSize" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(maxFrequencyHz <= sampleRate / 2.0) { "search range exceeds Nyquist frequency" }

        var energy = 0.0
        for (index in samples.indices) {
            val sample = samples[index]
            require(sample.isFinite()) { "samples must be finite" }
            windowed[index] = sample
            energy += sample * sample.toDouble()
        }
        if (energy <= SILENCE_ENERGY) return null

        window.applyInPlace(windowed)
        fft.magnitudeSpectrum(windowed, magnitudes)
        val coarse = strongestCandidate(sampleRate) ?: return null
        val refined = refineByPeriod(samples, sampleRate, coarse.frequencyHz)
        return PitchProposal(
            frequencyHz = refined.coerceIn(minFrequencyHz, maxFrequencyHz),
            salience = coarse.salience,
        )
    }

    private fun strongestCandidate(sampleRate: Int): PitchProposal? {
        val step = 2.0.pow(SEARCH_STEP_CENTS / 1200.0)
        var frequency = minFrequencyHz
        var bestFrequency = frequency
        var bestScore = 0.0
        while (frequency <= maxFrequencyHz) {
            val score = harmonicScore(frequency, sampleRate)
            if (score > bestScore) {
                bestScore = score
                bestFrequency = frequency
            }
            frequency *= step
        }
        return if (bestScore > 0.0) PitchProposal(bestFrequency, bestScore) else null
    }

    private fun harmonicScore(frequencyHz: Double, sampleRate: Int): Double {
        val nyquist = sampleRate / 2.0
        var score = 0.0
        var harmonic = 1
        while (harmonic <= MAX_HARMONICS && harmonic * frequencyHz <= nyquist) {
            val bin = harmonic * frequencyHz * frameSize / sampleRate
            score += interpolatedMagnitude(bin) / sqrt(harmonic.toDouble())
            harmonic++
        }
        return score
    }

    private fun interpolatedMagnitude(bin: Double): Double {
        val lower = bin.toInt().coerceIn(0, magnitudes.lastIndex)
        if (lower == magnitudes.lastIndex) return magnitudes[lower].toDouble()
        val fraction = bin - lower
        return magnitudes[lower] * (1.0 - fraction) + magnitudes[lower + 1] * fraction
    }

    private fun refineByPeriod(samples: FloatArray, sampleRate: Int, frequencyHz: Double): Double {
        val centerLag = sampleRate / frequencyHz
        val minLag = (centerLag * 0.92).toInt().coerceAtLeast(1)
        val maxLag = (centerLag * 1.08).toInt().coerceAtMost(samples.lastIndex - 1)
        var bestLag = minLag
        var bestCorrelation = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val correlation = normalizedCorrelation(samples, lag)
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }
        val offset = parabolicOffset(
            normalizedCorrelation(samples, bestLag - 1),
            bestCorrelation,
            normalizedCorrelation(samples, bestLag + 1),
        )
        return sampleRate / (bestLag + offset)
    }

    private fun normalizedCorrelation(samples: FloatArray, lag: Int): Double {
        if (lag < 1 || lag >= samples.size) return -1.0
        var product = 0.0
        var firstEnergy = 0.0
        var secondEnergy = 0.0
        for (index in 0 until samples.size - lag) {
            val first = samples[index].toDouble()
            val second = samples[index + lag].toDouble()
            product += first * second
            firstEnergy += first * first
            secondEnergy += second * second
        }
        val denominator = sqrt(firstEnergy * secondEnergy)
        return if (denominator > 0.0) product / denominator else -1.0
    }

    private fun parabolicOffset(left: Double, center: Double, right: Double): Double {
        val denominator = left - 2.0 * center + right
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < 1e-12) return 0.0
        return (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
    }

    private companion object {
        const val MAX_HARMONICS = 8
        const val SEARCH_STEP_CENTS = 1.0
        const val SILENCE_ENERGY = 1e-12
    }
}
