package com.cuwano.afinador.audio

/**
 * Converts linear 16-bit PCM samples into the normalized float contract used by [AudioInput].
 *
 * A signed 16-bit sample spans -32768..32767, so dividing by [FULL_SCALE] maps
 * `Short.MIN_VALUE` to exactly -1.0 and `Short.MAX_VALUE` to 32767/32768. The result is
 * therefore already inside [-1, 1]; the clamp only guards the contract if the scale changes.
 */
object Pcm16 {
    /** Number of distinct 16-bit values, used as the normalization divisor. */
    const val FULL_SCALE: Float = 32768f

    /** Converts the first [count] samples, in order, into a new array. */
    fun toFloats(samples: ShortArray, count: Int = samples.size): FloatArray {
        require(count in 0..samples.size) { "count $count must be within 0..${samples.size}" }
        val converted = FloatArray(count)
        toFloats(samples, count, converted, 0)
        return converted
    }

    /** Converts the first [count] samples into [destination] without allocating. */
    fun toFloats(
        samples: ShortArray,
        count: Int,
        destination: FloatArray,
        destinationOffset: Int = 0,
    ) {
        require(count in 0..samples.size) { "count $count must be within 0..${samples.size}" }
        require(destinationOffset >= 0 && destinationOffset + count <= destination.size) {
            "destination range does not fit in ${destination.size} samples"
        }
        for (index in 0 until count) {
            destination[destinationOffset + index] = (samples[index] / FULL_SCALE).coerceIn(-1f, 1f)
        }
    }
}
