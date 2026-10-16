package com.cuwano.afinador.dsp

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

internal object SignalFixtures {
    fun sine(
        frequencyHz: Double,
        sampleRate: Int,
        size: Int,
        amplitude: Double = 1.0,
        phaseRadians: Double = 0.0,
    ): FloatArray = FloatArray(size) { index ->
        (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRate + phaseRadians)).toFloat()
    }

    fun harmonicMixture(
        fundamentalHz: Double,
        sampleRate: Int,
        size: Int,
        harmonicAmplitudes: List<Double>,
        phaseRadians: Double = 0.0,
    ): FloatArray {
        val result = FloatArray(size)
        harmonicAmplitudes.forEachIndexed { index, amplitude ->
            val harmonic = sine(
                frequencyHz = fundamentalHz * (index + 1),
                sampleRate = sampleRate,
                size = size,
                amplitude = amplitude,
                phaseRadians = phaseRadians * (index + 1),
            )
            harmonic.indices.forEach { sample -> result[sample] += harmonic[sample] }
        }
        return result
    }

    fun whiteNoise(size: Int, amplitude: Double, seed: Long): FloatArray {
        val random = Random(seed)
        return FloatArray(size) { (amplitude * (2.0 * random.nextDouble() - 1.0)).toFloat() }
    }

    fun mix(vararg signals: FloatArray): FloatArray {
        require(signals.isNotEmpty()) { "at least one signal is required" }
        require(signals.all { it.size == signals.first().size }) { "signals must have equal sizes" }
        return FloatArray(signals.first().size) { index ->
            signals.sumOf { it[index].toDouble() }.toFloat()
        }
    }
}
