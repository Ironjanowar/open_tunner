package com.cuwano.afinador.dsp

import kotlin.math.PI
import kotlin.math.cos

class HannWindow(val size: Int) {
    private val coefficients: FloatArray

    init {
        require(size > 1) { "size must be greater than one" }
        coefficients = FloatArray(size) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (size - 1))).toFloat()
        }
    }

    fun applyInPlace(samples: FloatArray) {
        require(samples.size == size) { "samples must match window size" }
        for (index in samples.indices) {
            samples[index] *= coefficients[index]
        }
    }
}
