package com.cuwano.afinador.dsp

import kotlin.math.sqrt

/**
 * Scales a frame toward a target RMS so quiet playing is detected without
 * asking the player to play loudly.
 *
 * Normalisation only ever amplifies: a loud frame is left untouched, so a
 * well-leveled player is unaffected. The frame RMS is measured on the DC-free
 * signal — the constant bias is the frame mean, accumulated in `Double` — so a
 * large DC offset can no longer make a quiet frame look loud and silently
 * disable the boost, and a lone spike or attack transient cannot do so either.
 *
 * The prefix `[0, count)` is scaled in place by
 * `clamp(targetRms / dcFreeRms, 1.0, maximumGain)` and that same factor is
 * returned, so the caller can reason about how much gain was applied. The
 * factor is quantised to the sample precision, and every scaled sample is
 * clamped into `[-1, 1]` so normalisation can never leave full scale. A silent
 * frame (RMS below a small epsilon) or an empty request is a no-op: the buffer
 * is not touched and unity gain is returned.
 */
class InputNormalizer(val targetRms: Double = DEFAULT_TARGET_RMS) {
    var maximumGain: Double = DEFAULT_MAXIMUM_GAIN
        set(value) {
            require(value.isFinite() && value > 1.0) {
                "maximumGain must be finite and greater than one"
            }
            field = value
        }

    init {
        require(targetRms.isFinite() && targetRms > 0.0) {
            "targetRms must be finite and positive"
        }
    }

    fun normalize(samples: FloatArray, count: Int = samples.size): Double {
        require(count in 0..samples.size) { "count must be between zero and samples size" }
        if (count == 0) return 1.0
        val frameRms = dcFreeRms(samples, count)
        if (frameRms <= SILENCE_EPSILON) return 1.0
        val gain = (targetRms / frameRms).coerceIn(1.0, maximumGain).toFloat().toDouble()
        if (gain <= 1.0) return 1.0
        for (index in 0 until count) {
            samples[index] = (samples[index] * gain).toFloat().coerceIn(-1f, 1f)
        }
        return gain
    }

    /**
     * RMS of the frame measured around its true mean. The mean is accumulated in
     * `Double`, so a single large spike or a note attack transient (both rare
     * outliers) can shift it only by their contribution divided by the frame
     * size — never enough to read a loud "level" off a quiet frame and silently
     * drop the boost. The mean is exactly the constant bias of a DC-pedestalled
     * frame, so the same estimator measures the AC content of any frame, offset
     * or not.
     */
    private fun dcFreeRms(samples: FloatArray, count: Int): Double {
        var sum = 0.0
        for (index in 0 until count) sum += samples[index].toDouble()
        val mean = sum / count
        var squaredSum = 0.0
        for (index in 0 until count) {
            val centered = samples[index].toDouble() - mean
            squaredSum += centered * centered
        }
        return sqrt(squaredSum / count)
    }

    private companion object {
        const val DEFAULT_TARGET_RMS = 0.12
        const val DEFAULT_MAXIMUM_GAIN = 2.0
        const val SILENCE_EPSILON = 1e-9
    }
}
