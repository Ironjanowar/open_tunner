package com.cuwano.afinador.dsp

import kotlin.math.abs
import kotlin.math.sqrt

data class SignalMeasurement(
    val dcOffset: Double,
    val rms: Double,
    val clipped: Boolean,
)

object SignalMetrics {
    fun analyze(
        samples: FloatArray,
        dcRemoved: FloatArray,
        clippingThreshold: Float = 0.99f,
    ): SignalMeasurement {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        require(dcRemoved.size == samples.size) { "dcRemoved must match samples size" }
        require(clippingThreshold.isFinite() && clippingThreshold > 0f) {
            "clippingThreshold must be finite and positive"
        }

        val dcOffset = samples.sumOf { it.toDouble() } / samples.size
        var squaredSum = 0.0
        var clipped = false

        samples.forEachIndexed { index, sample ->
            val centered = sample.toDouble() - dcOffset
            dcRemoved[index] = centered.toFloat()
            squaredSum += centered * centered
            if (abs(sample) >= clippingThreshold) clipped = true
        }

        return SignalMeasurement(
            dcOffset = dcOffset,
            rms = sqrt(squaredSum / samples.size),
            clipped = clipped,
        )
    }
}
