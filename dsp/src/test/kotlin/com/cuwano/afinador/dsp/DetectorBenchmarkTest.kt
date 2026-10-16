package com.cuwano.afinador.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectorBenchmarkTest {
    @Test
    fun `engine performs exactly one analysis for each complete hop`() {
        val sampleRate = 48_000
        val engine = newEngine(sampleRate)
        val callbacks = mutableListOf<TunerReading?>()
        val completeHopCount = 17
        val trailingSamples = HOP_SIZE - 1
        val validSamples = FRAME_SIZE + completeHopCount * HOP_SIZE + trailingSamples
        val signal = sine(220.0, sampleRate, validSamples)
        var offset = 0
        val chunkPattern = intArrayOf(3, 4095, 17, 1024, 211, 1, 700)
        var chunkIndex = 0

        while (offset < signal.size) {
            val count = minOf(chunkPattern[chunkIndex % chunkPattern.size], signal.size - offset)
            val captureBuffer = FloatArray(count + 23) { -999f }
            signal.copyInto(captureBuffer, startIndex = offset, endIndex = offset + count)
            engine.accept(captureBuffer, count, callbacks::add)
            offset += count
            chunkIndex++
        }

        val expectedAnalyses = 1 + (validSamples - FRAME_SIZE) / HOP_SIZE
        assertEquals(expectedAnalyses, callbacks.size)
        assertEquals(completeHopCount + 1, callbacks.size)
    }

    @Test
    fun `benchmark records each frame duration without a fragile wall clock budget`() {
        val sampleRate = 48_000
        val engine = newEngine(sampleRate)
        val frameTimingsNanos = mutableListOf<Long>()
        var analyzedFrames = 0

        // Untimed warmup exercises the same integrated path before recording.
        engine.accept(sine(440.0, sampleRate, FRAME_SIZE + 3 * HOP_SIZE)) { }
        engine.reset()

        val first = sine(440.0, sampleRate, FRAME_SIZE)
        var before = System.nanoTime()
        engine.accept(first) {
            frameTimingsNanos += System.nanoTime() - before
            analyzedFrames++
        }

        repeat(BENCHMARK_FRAMES - 1) { hopIndex ->
            val hop = FloatArray(HOP_SIZE) { sampleInHop ->
                val absoluteSample = FRAME_SIZE + hopIndex * HOP_SIZE + sampleInHop
                (0.65 * sin(2.0 * PI * 440.0 * absoluteSample / sampleRate)).toFloat()
            }
            before = System.nanoTime()
            engine.accept(hop) {
                frameTimingsNanos += System.nanoTime() - before
                analyzedFrames++
            }
        }

        assertEquals(BENCHMARK_FRAMES, analyzedFrames)
        assertEquals(BENCHMARK_FRAMES, frameTimingsNanos.size)
        assertTrue(frameTimingsNanos.all { it >= 0L })

        val sorted = frameTimingsNanos.sorted()
        val median = sorted[sorted.size / 2]
        val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
        val p95 = sorted[p95Index]
        println(
            "TunerEngine benchmark: frames=${sorted.size}, " +
                "perFrameNanos=${frameTimingsNanos.joinToString(prefix = "[", postfix = "]")}, " +
                "medianNanos=$median, p95Nanos=$p95",
        )
        // Deliberately no absolute timing assertion: host/JIT/CI timing is not a device budget.
    }

    private fun newEngine(sampleRate: Int) = TunerEngine(
        sampleRate = sampleRate,
        frameSize = FRAME_SIZE,
        hopSize = HOP_SIZE,
        minFrequencyHz = 40.0,
        maxFrequencyHz = 1500.0,
    )

    private fun sine(frequencyHz: Double, sampleRate: Int, size: Int): FloatArray =
        FloatArray(size) { index ->
            (0.65 * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
        }

    private companion object {
        const val FRAME_SIZE = 4096
        const val HOP_SIZE = 1024
        const val BENCHMARK_FRAMES = 12
    }
}
