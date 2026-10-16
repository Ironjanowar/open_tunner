package com.cuwano.afinador.dsp

class SampleRingBuffer(
    private val frameSize: Int,
    private val hopSize: Int,
) {
    init {
        require(frameSize > 0) { "frameSize must be positive" }
        require(hopSize > 0) { "hopSize must be positive" }
    }

    private val samples = FloatArray(frameSize)
    private val frame = FloatArray(frameSize)
    private var writeIndex = 0
    private var sampleCount = 0L

    fun accept(
        input: FloatArray,
        count: Int = input.size,
        onFrame: (FloatArray) -> Unit,
    ) {
        require(count in 0..input.size) { "count must be between zero and input size" }

        repeat(count) { inputIndex ->
            samples[writeIndex] = input[inputIndex]
            writeIndex = (writeIndex + 1) % frameSize
            sampleCount++

            if (sampleCount >= frameSize && (sampleCount - frameSize) % hopSize == 0L) {
                copyFrame()
                onFrame(frame)
            }
        }
    }

    fun reset() {
        writeIndex = 0
        sampleCount = 0
    }

    private fun copyFrame() {
        for (index in frame.indices) {
            frame[index] = samples[(writeIndex + index) % frameSize]
        }
    }
}
