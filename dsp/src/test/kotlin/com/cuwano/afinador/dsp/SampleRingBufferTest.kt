package com.cuwano.afinador.dsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SampleRingBufferTest {
    @Test
    fun `waits for a complete frame across partial chunks`() {
        val buffer = SampleRingBuffer(frameSize = 4, hopSize = 2)
        val frames = mutableListOf<FloatArray>()

        buffer.accept(floatArrayOf(0f, 1f)) { frames += it.copyOf() }
        buffer.accept(floatArrayOf(2f)) { frames += it.copyOf() }
        assertEquals(0, frames.size)

        buffer.accept(floatArrayOf(3f)) { frames += it.copyOf() }

        assertEquals(1, frames.size)
        assertContentEquals(floatArrayOf(0f, 1f, 2f, 3f), frames.single())
    }

    @Test
    fun `emits overlapping frames at each complete hop`() {
        val buffer = SampleRingBuffer(frameSize = 4, hopSize = 2)
        val frames = collectFrames(buffer, floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f))

        assertFramesEqual(
            listOf(
                floatArrayOf(0f, 1f, 2f, 3f),
                floatArrayOf(2f, 3f, 4f, 5f),
                floatArrayOf(4f, 5f, 6f, 7f),
            ),
            frames,
        )
    }

    @Test
    fun `preserves sample order when internal storage wraps`() {
        val buffer = SampleRingBuffer(frameSize = 5, hopSize = 3)
        val frames = mutableListOf<FloatArray>()

        (0 until 14).forEach { sample ->
            buffer.accept(floatArrayOf(sample.toFloat())) { frames += it.copyOf() }
        }

        assertFramesEqual(
            listOf(
                floatArrayOf(0f, 1f, 2f, 3f, 4f),
                floatArrayOf(3f, 4f, 5f, 6f, 7f),
                floatArrayOf(6f, 7f, 8f, 9f, 10f),
                floatArrayOf(9f, 10f, 11f, 12f, 13f),
            ),
            frames,
        )
    }

    @Test
    fun `reset discards buffered samples and restarts framing`() {
        val buffer = SampleRingBuffer(frameSize = 4, hopSize = 2)
        val frames = mutableListOf<FloatArray>()

        buffer.accept(floatArrayOf(0f, 1f, 2f)) { frames += it.copyOf() }
        buffer.reset()
        buffer.accept(floatArrayOf(10f, 11f, 12f)) { frames += it.copyOf() }
        assertEquals(0, frames.size)

        buffer.accept(floatArrayOf(13f)) { frames += it.copyOf() }

        assertFramesEqual(listOf(floatArrayOf(10f, 11f, 12f, 13f)), frames)
    }

    @Test
    fun `frame output is invariant to input chunking`() {
        val signal = FloatArray(19) { it.toFloat() }
        val allAtOnce = collectFrames(SampleRingBuffer(frameSize = 6, hopSize = 4), signal)
        val chunked = mutableListOf<FloatArray>()
        val chunkedBuffer = SampleRingBuffer(frameSize = 6, hopSize = 4)
        var offset = 0

        listOf(1, 5, 2, 8, 3).forEach { chunkSize ->
            val chunk = signal.copyOfRange(offset, offset + chunkSize)
            chunkedBuffer.accept(chunk, chunk.size) { chunked += it.copyOf() }
            offset += chunkSize
        }

        assertEquals(signal.size, offset)
        assertFramesEqual(allAtOnce, chunked)
    }

    @Test
    fun `count limits valid samples in a partially filled capture array`() {
        val buffer = SampleRingBuffer(frameSize = 4, hopSize = 2)
        val frames = mutableListOf<FloatArray>()

        buffer.accept(floatArrayOf(0f, 1f, 99f, 99f), count = 2) { frames += it.copyOf() }
        buffer.accept(floatArrayOf(2f, 3f, 99f), count = 2) { frames += it.copyOf() }

        assertFramesEqual(listOf(floatArrayOf(0f, 1f, 2f, 3f)), frames)
    }

    private fun collectFrames(buffer: SampleRingBuffer, samples: FloatArray): List<FloatArray> =
        buildList {
            buffer.accept(samples, samples.size) { add(it.copyOf()) }
        }

    private fun assertFramesEqual(expected: List<FloatArray>, actual: List<FloatArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (expectedFrame, actualFrame) ->
            assertContentEquals(expectedFrame, actualFrame, "frame $index")
        }
    }
}
