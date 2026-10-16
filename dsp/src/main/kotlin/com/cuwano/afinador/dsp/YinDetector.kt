package com.cuwano.afinador.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/** A periodicity observation produced by the YIN difference function. */
data class YinObservation(
    val frequencyHz: Double,
    val confidence: Double,
    val cmnd: Double,
) {
    fun hasOctaveDisagreement(primaryFrequencyHz: Double): Boolean {
        if (!primaryFrequencyHz.isFinite() || primaryFrequencyHz <= 0.0) return false
        val octaveDistance = abs(ln(primaryFrequencyHz / frequencyHz) / ln(2.0))
        return abs(octaveDistance - 1.0) <= OCTAVE_TOLERANCE
    }

    private companion object {
        const val OCTAVE_TOLERANCE = 0.08
    }
}

/** Allocation-free-after-construction implementation of the YIN pitch detector. */
class YinDetector(
    private val frameSize: Int,
    private val minFrequencyHz: Double,
    private val maxFrequencyHz: Double,
    private val cmndThreshold: Double,
) {
    private val difference = DoubleArray(frameSize)
    private val cmnd = DoubleArray(frameSize)

    init {
        require(frameSize >= 4) { "frameSize must be at least four" }
        require(minFrequencyHz.isFinite() && minFrequencyHz > 0.0) {
            "minFrequencyHz must be finite and positive"
        }
        require(maxFrequencyHz.isFinite() && maxFrequencyHz > minFrequencyHz) {
            "maxFrequencyHz must be finite and greater than minFrequencyHz"
        }
        require(cmndThreshold.isFinite() && cmndThreshold in 0.0..1.0) {
            "cmndThreshold must be between zero and one"
        }
    }

    fun analyze(samples: FloatArray, sampleRate: Int): YinObservation? {
        require(samples.size == frameSize) { "samples must match frameSize" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(maxFrequencyHz <= sampleRate / 2.0) { "search range exceeds Nyquist frequency" }

        var energy = 0.0
        for (sample in samples) {
            require(sample.isFinite()) { "samples must be finite" }
            energy += sample * sample.toDouble()
        }
        if (energy <= SILENCE_ENERGY) return null

        // Include the integer lags surrounding each real-valued endpoint so
        // parabolic refinement can reach the configured inclusive range.
        val minLag = floor(sampleRate / maxFrequencyHz).toInt().coerceAtLeast(2)
        val maxLag = ceil(sampleRate / minFrequencyHz).toInt().coerceAtMost(frameSize - 2)
        if (minLag > maxLag) return null

        // One sample beyond the inclusive search range lets interpolation
        // distinguish a true endpoint minimum from a minimum beyond it.
        val calculatedMaxLag = (maxLag + 1).coerceAtMost(frameSize - 1)
        calculateDifference(samples, calculatedMaxLag)
        calculateCmnd(calculatedMaxLag)
        val lag = firstThresholdMinimum(minLag, maxLag) ?: return null
        val refinedLag = interpolateLag(lag, calculatedMaxLag)
        val frequency = sampleRate / refinedLag
        val minimumAccepted = minFrequencyHz * (1.0 - RANGE_EPSILON_RATIO)
        val maximumAccepted = maxFrequencyHz * (1.0 + RANGE_EPSILON_RATIO)
        if (!frequency.isFinite() || frequency !in minimumAccepted..maximumAccepted) return null
        val boundedFrequency = frequency.coerceIn(minFrequencyHz, maxFrequencyHz)

        val selectedCmnd = cmnd[lag].coerceIn(0.0, 1.0)
        return YinObservation(boundedFrequency, 1.0 - selectedCmnd, selectedCmnd)
    }

    private fun calculateDifference(samples: FloatArray, maxLag: Int) {
        difference[0] = 0.0
        for (lag in 1..maxLag) {
            var sum = 0.0
            for (index in 0 until samples.size - lag) {
                val delta = samples[index] - samples[index + lag]
                sum += delta * delta.toDouble()
            }
            difference[lag] = sum
        }
    }

    private fun calculateCmnd(maxLag: Int) {
        cmnd[0] = 1.0
        var runningSum = 0.0
        for (lag in 1..maxLag) {
            runningSum += difference[lag]
            cmnd[lag] = if (runningSum > 0.0) difference[lag] * lag / runningSum else 1.0
        }
    }

    private fun firstThresholdMinimum(minLag: Int, maxLag: Int): Int? {
        var lag = minLag
        while (lag <= maxLag) {
            if (cmnd[lag] <= cmndThreshold) {
                while (lag < maxLag && cmnd[lag + 1] < cmnd[lag]) lag++
                return lag
            }
            lag++
        }
        return null
    }

    private fun interpolateLag(lag: Int, maxLag: Int): Double {
        if (lag <= 1 || lag >= maxLag) return lag.toDouble()
        // Interpolate the unnormalized difference valley. The CMND curve has
        // a slight lag-dependent slope that biases boundary estimates.
        val left = difference[lag - 1]
        val center = difference[lag]
        val right = difference[lag + 1]
        val denominator = left - 2.0 * center + right
        if (!denominator.isFinite() || abs(denominator) < 1e-15) return lag.toDouble()
        val offset = (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
        return max(1.0, lag + offset)
    }

    private companion object {
        const val SILENCE_ENERGY = 1e-12
        const val RANGE_EPSILON_RATIO = 2e-5
    }
}
