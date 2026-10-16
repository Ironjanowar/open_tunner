package com.cuwano.afinador.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class Radix2RealFft(val size: Int) {
    val spectrumSize: Int = size / 2 + 1

    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val bitReversed = IntArray(size)
    private val cosine = DoubleArray(size / 2)
    private val sine = DoubleArray(size / 2)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (index in 0 until size) {
            bitReversed[index] = Integer.reverse(index) ushr (Int.SIZE_BITS - bits)
        }
        for (index in cosine.indices) {
            val angle = -2.0 * PI * index / size
            cosine[index] = cos(angle)
            sine[index] = sin(angle)
        }
    }

    fun magnitudeSpectrum(samples: FloatArray, magnitudes: FloatArray) {
        require(samples.size == size) { "samples must match FFT size" }
        require(magnitudes.size == spectrumSize) { "magnitudes must match spectrum size" }

        copyBitReversed(samples)
        transform()
        for (bin in magnitudes.indices) {
            magnitudes[bin] = hypot(real[bin], imaginary[bin]).toFloat()
        }
    }

    fun binFrequencyHz(bin: Int, sampleRate: Int): Double {
        require(bin in 0 until spectrumSize) { "bin is outside the real spectrum" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        return bin * sampleRate.toDouble() / size
    }

    private fun copyBitReversed(samples: FloatArray) {
        for (index in samples.indices) {
            real[bitReversed[index]] = samples[index].toDouble()
            imaginary[bitReversed[index]] = 0.0
        }
    }

    private fun transform() {
        var blockSize = 2
        while (blockSize <= size) {
            transformBlocks(blockSize)
            blockSize = blockSize shl 1
        }
    }

    private fun transformBlocks(blockSize: Int) {
        val half = blockSize / 2
        val twiddleStep = size / blockSize
        var start = 0
        while (start < size) {
            for (offset in 0 until half) {
                butterfly(start + offset, start + offset + half, offset * twiddleStep)
            }
            start += blockSize
        }
    }

    private fun butterfly(even: Int, odd: Int, twiddle: Int) {
        val oddReal = real[odd] * cosine[twiddle] - imaginary[odd] * sine[twiddle]
        val oddImaginary = real[odd] * sine[twiddle] + imaginary[odd] * cosine[twiddle]
        val evenReal = real[even]
        val evenImaginary = imaginary[even]
        real[even] = evenReal + oddReal
        imaginary[even] = evenImaginary + oddImaginary
        real[odd] = evenReal - oddReal
        imaginary[odd] = evenImaginary - oddImaginary
    }
}
