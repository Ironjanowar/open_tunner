package com.cuwano.afinador.audio

/** A mono, normalized floating-point audio stream. */
interface AudioInput {
    /** The rate actually used by the capture device. */
    val sampleRate: Int

    fun start()

    /** Reads into [buffer] and returns the number of valid samples, or a negative error code. */
    fun read(buffer: FloatArray): Int

    fun stop()

    fun release()
}

fun interface AudioInputFactory {
    fun create(requestedSampleRate: Int): AudioInput
}
