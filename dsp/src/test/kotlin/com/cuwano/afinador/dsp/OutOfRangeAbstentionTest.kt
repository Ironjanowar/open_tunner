package com.cuwano.afinador.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrated abstention contract for tones outside the configured 40-1500 Hz
 * search range.
 *
 * Desired engine behaviour (RED until a small production change lands):
 *  1. `HarmonicSalienceDetector` clamps its proposal into `[min, max]`, so a
 *     pure tone below 40 Hz (or above 1500 Hz) can still be reported as an
 *     in-range note (e.g. a 30 Hz tone becomes a 40.0 Hz reading, a 2000 Hz
 *     tone becomes 1000.0 Hz).
 *  2. `PitchTracker.reliablePrimary` accepts that clamped proposal because it
 *     only consults salience and SNR, never whether the dominant spectral peak
 *     or YIN actually agrees with a frequency inside the range.
 *
 * Wished-for minimal fix (no new public API required): the engine must confirm
 * the dominant peak / YIN observation falls inside `[min, max]` before a
 * proposal is allowed to become a reading - i.e. an explicit in-range
 * dominant-peak check (or an equivalent availability flag) gating the tracker
 * input. These tests pin that behaviour only through the public
 * `TunerEngine.accept` boundary, so they keep compiling while the fix stays
 * internal.
 */
class OutOfRangeAbstentionTest {
    @Test
    fun `pure tones below the range never surface as in-range notes`() {
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(20.0, 30.0).forEach { frequencyHz ->
                val callbacks = runSignal(newEngine(sampleRate), sine(frequencyHz, sampleRate))
                val readings = callbacks.filterNotNull()

                assertTrue(
                    callbacks.isNotEmpty(),
                    "$frequencyHz Hz at $sampleRate Hz produced no analyzed frames",
                )
                assertTrue(
                    readings.isEmpty(),
                    "$frequencyHz Hz at $sampleRate Hz must abstain but produced " +
                        "${readings.size}/${callbacks.size} readings, first=${readings.firstOrNull()}",
                )
            }
        }
    }

    @Test
    fun `pure tones above the range never surface as in-range notes`() {
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(1700.0, 2000.0).forEach { frequencyHz ->
                val callbacks = runSignal(newEngine(sampleRate), sine(frequencyHz, sampleRate))
                val readings = callbacks.filterNotNull()

                assertTrue(
                    callbacks.isNotEmpty(),
                    "$frequencyHz Hz at $sampleRate Hz produced no analyzed frames",
                )
                assertTrue(
                    readings.isEmpty(),
                    "$frequencyHz Hz at $sampleRate Hz must abstain but produced " +
                        "${readings.size}/${callbacks.size} readings, first=${readings.firstOrNull()}",
                )
            }
        }
    }

    @Test
    fun `pure tones exactly at the range boundaries are still detected`() {
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(40.0, 1500.0).forEach { frequencyHz ->
                val callbacks = runSignal(newEngine(sampleRate), sine(frequencyHz, sampleRate))
                val readings = callbacks.filterNotNull()

                assertTrue(
                    readings.isNotEmpty(),
                    "$frequencyHz Hz at $sampleRate Hz is inside the range but was never detected",
                )
                val accurate = readings.count { abs(centsError(frequencyHz, it.frequencyHz)) <= 5.0 }
                val coverage = accurate.toDouble() / readings.size
                assertTrue(
                    coverage >= 0.9,
                    "$frequencyHz Hz at $sampleRate Hz: only $accurate/${readings.size} boundary " +
                        "readings within 5 cents ($coverage)",
                )
            }
        }
    }

    private fun newEngine(sampleRate: Int) = TunerEngine(
        sampleRate = sampleRate,
        frameSize = FRAME_SIZE,
        hopSize = HOP_SIZE,
        minFrequencyHz = MIN_FREQUENCY_HZ,
        maxFrequencyHz = MAX_FREQUENCY_HZ,
    )

    private fun sine(frequencyHz: Double, sampleRate: Int): FloatArray =
        FloatArray(samplesFor(SECONDS, sampleRate)) { index ->
            (AMPLITUDE * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
        }

    private fun runSignal(engine: TunerEngine, signal: FloatArray): List<TunerReading?> {
        val readings = mutableListOf<TunerReading?>()
        var offset = 0
        var chunkIndex = 0
        while (offset < signal.size) {
            val count = minOf(CHUNKS[chunkIndex % CHUNKS.size], signal.size - offset)
            val captureBuffer = FloatArray(count)
            signal.copyInto(captureBuffer, endIndex = offset + count, startIndex = offset)
            engine.accept(captureBuffer, count, readings::add)
            offset += count
            chunkIndex++
        }
        return readings
    }

    private fun centsError(expectedHz: Double, actualHz: Double): Double =
        1200.0 * ln(actualHz / expectedHz) / ln(2.0)

    private fun samplesFor(seconds: Double, sampleRate: Int): Int = (seconds * sampleRate).toInt()

    private companion object {
        const val FRAME_SIZE = 4096
        const val HOP_SIZE = 1024
        const val MIN_FREQUENCY_HZ = 40.0
        const val MAX_FREQUENCY_HZ = 1500.0
        const val SECONDS = 0.4
        const val AMPLITUDE = 0.70
        val CHUNKS = intArrayOf(4096, 137, 1024, 511, 73)
        val SAMPLE_RATES = intArrayOf(44_100, 48_000)
    }
}
