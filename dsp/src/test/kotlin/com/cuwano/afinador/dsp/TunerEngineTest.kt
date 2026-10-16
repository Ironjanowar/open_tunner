package com.cuwano.afinador.dsp

import java.lang.reflect.Modifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunerEngineTest {
    @Test
    fun `clean sustained corpus meets accuracy at both capture rates after warmup`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val cases = listOf(
                80.0 to 3.0,
                110.0 to 3.0,
                220.0 to 3.0,
                440.0 to 3.0,
                880.0 to 3.0,
                1000.0 to 3.0,
                40.0 to 5.0,
                1500.0 to 5.0,
            )
            cases.forEach { (frequencyHz, toleranceCents) ->
                val signal = continuousSine(frequencyHz, sampleRate, seconds = 0.55, amplitude = 0.70)
                val callbacks = runSignal(newEngine(sampleRate), signal)
                assertAccurateCoverage(
                    callbacks = callbacks,
                    expectedHz = frequencyHz,
                    toleranceCents = toleranceCents,
                    sampleRate = sampleRate,
                    warmupSeconds = 0.25,
                    minimumCoverage = 0.95,
                    label = "$frequencyHz Hz at $sampleRate Hz",
                )
            }
        }
    }

    @Test
    fun `harmonic and missing fundamental corpus has at least ninety five percent nominal readings`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val corpus = listOf(
                Triple(110.0, listOf(0.42, 0.23, 0.13, 0.07, 0.04), 0.17),
                Triple(220.0, listOf(0.34, 0.29, 0.14, 0.08, 0.04), 0.43),
                Triple(110.0, listOf(0.0, 0.38, 0.27, 0.17, 0.10, 0.03), 0.71),
            )
            corpus.forEachIndexed { index, (frequencyHz, harmonics, phase) ->
                val signal = continuousHarmonicMixture(
                    fundamentalHz = frequencyHz,
                    sampleRate = sampleRate,
                    seconds = 0.60,
                    harmonicAmplitudes = harmonics,
                    phaseRadians = phase,
                )
                assertAccurateCoverage(
                    callbacks = runSignal(newEngine(sampleRate), signal),
                    expectedHz = frequencyHz,
                    toleranceCents = 5.0,
                    sampleRate = sampleRate,
                    warmupSeconds = 0.25,
                    minimumCoverage = 0.95,
                    label = "harmonic case $index at $sampleRate Hz",
                )
            }
        }
    }

    @Test
    fun `silence emits analyzed states but never a note`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val callbacks = runSignal(newEngine(sampleRate), FloatArray(sampleRate))

            assertTrue(callbacks.isNotEmpty(), "silence must still account for analyzed frames")
            assertTrue(callbacks.all { it == null }, "silence produced a tuner reading at $sampleRate Hz")
        }
    }

    @Test
    fun `seeded noise produces fewer than one percent stable false notes`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val noise = SignalFixtures.whiteNoise(sampleRate * 2, amplitude = 0.035, seed = 0x51A1E5L)
            val callbacks = runSignal(newEngine(sampleRate), noise, chunks = intArrayOf(137, 509, 31, 2048, 7))
            val stableFalseNotes = callbacks.count { it != null }
            val falseNoteRate = stableFalseNotes.toDouble() / callbacks.size

            assertTrue(
                falseNoteRate < 0.01,
                "seeded noise produced $stableFalseNotes/${callbacks.size} stable notes ($falseNoteRate) at $sampleRate Hz",
            )
        }
    }

    @Test
    fun `reading stream is invariant to capture chunking and count ignores unused tail`() {
        val sampleRate = 48_000
        val signal = continuousHarmonicMixture(
            fundamentalHz = 220.0,
            sampleRate = sampleRate,
            seconds = 0.50,
            harmonicAmplitudes = listOf(0.45, 0.24, 0.13, 0.07),
            phaseRadians = 0.37,
        )
        val whole = runSignal(newEngine(sampleRate), signal, chunks = intArrayOf(signal.size))
        val chunked = runSignal(
            newEngine(sampleRate),
            signal,
            chunks = intArrayOf(1, 137, 1024, 17, 4093, 511),
            appendUnusedTail = true,
        )

        assertEquals(whole.size, chunked.size)
        whole.zip(chunked).forEachIndexed { index, (expected, actual) ->
            if (expected == null) {
                assertNull(actual, "frame $index")
            } else {
                val reading = assertNotNull(actual, "frame $index")
                assertEquals(expected.noteName, reading.noteName, "frame $index")
                assertEquals(expected.octave, reading.octave, "frame $index")
                assertEquals(expected.frequencyHz, reading.frequencyHz, 1e-12, "frame $index")
                assertEquals(expected.cents, reading.cents, 1e-12, "frame $index")
                assertEquals(expected.confidence, reading.confidence, 1e-12, "frame $index")
                assertEquals(expected.isTuned, reading.isTuned, "frame $index")
                assertEquals(expected.rms, reading.rms, 1e-12, "frame $index")
                assertEquals(expected.clipped, reading.clipped, "frame $index")
            }
        }
    }

    @Test
    fun `first clean reading arrives within three hundred milliseconds measured in samples`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val callbacks = runSignal(
                newEngine(sampleRate),
                continuousSine(440.0, sampleRate, seconds = 0.45, amplitude = 0.70),
            )
            val firstReadingFrame = callbacks.indexOfFirst { it != null }
            assertTrue(firstReadingFrame >= 0, "no first reading at $sampleRate Hz")
            val firstReadingEndSample = FRAME_SIZE + firstReadingFrame * HOP_SIZE

            assertTrue(
                firstReadingEndSample <= samplesFor(0.300, sampleRate),
                "first reading ended at sample $firstReadingEndSample at $sampleRate Hz",
            )
        }
    }

    @Test
    fun `a real note change is reported within three hundred milliseconds of its input sample`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val changeSample = FRAME_SIZE + 12 * HOP_SIZE
            val totalSamples = changeSample + samplesFor(0.45, sampleRate)
            val signal = FloatArray(totalSamples) { index ->
                val frequency = if (index < changeSample) 440.0 else 660.0
                (0.70 * sin(2.0 * PI * frequency * index / sampleRate)).toFloat()
            }
            val callbacks = runSignal(newEngine(sampleRate), signal)
            val firstFrameEndingAfterChange =
                ((changeSample - FRAME_SIZE + HOP_SIZE - 1) / HOP_SIZE).coerceAtLeast(0)
            val changedFrame = (firstFrameEndingAfterChange until callbacks.size).firstOrNull { frameIndex ->
                callbacks[frameIndex]?.let { abs(centsError(660.0, it.frequencyHz)) <= 8.0 } == true
            }
            assertNotNull(changedFrame, "660 Hz change was never reported at $sampleRate Hz")
            val changedEndSample = FRAME_SIZE + changedFrame * HOP_SIZE

            assertTrue(
                changedEndSample - changeSample <= samplesFor(0.300, sampleRate),
                "note change took ${changedEndSample - changeSample} samples at $sampleRate Hz",
            )
        }
    }

    @Test
    fun `a stale note is released within three hundred fifty milliseconds of silence input`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val silenceStartSample = FRAME_SIZE + 12 * HOP_SIZE
            val signal = FloatArray(silenceStartSample + samplesFor(0.45, sampleRate)) { index ->
                if (index < silenceStartSample) {
                    (0.70 * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
                } else {
                    0f
                }
            }
            val callbacks = runSignal(newEngine(sampleRate), signal)
            val firstFrameEndingAfterSilence =
                ((silenceStartSample - FRAME_SIZE + HOP_SIZE - 1) / HOP_SIZE).coerceAtLeast(0)
            assertTrue(callbacks.take(firstFrameEndingAfterSilence).any { it != null }, "note was never acquired")
            val releasedFrame = (firstFrameEndingAfterSilence until callbacks.size).firstOrNull { callbacks[it] == null }
            assertNotNull(releasedFrame, "reading did not release at $sampleRate Hz")
            val releasedEndSample = FRAME_SIZE + releasedFrame * HOP_SIZE

            assertTrue(
                releasedEndSample - silenceStartSample <= samplesFor(0.350, sampleRate),
                "release took ${releasedEndSample - silenceStartSample} samples at $sampleRate Hz",
            )
        }
    }

    @Test
    fun `hard clipping is rejected or explicitly marked and degraded`() {
        val sampleRate = 48_000
        val clean = runSignal(
            newEngine(sampleRate),
            continuousSine(440.0, sampleRate, seconds = 0.45, amplitude = 0.70),
        ).filterNotNull()
        val clippedSignal = FloatArray(samplesFor(0.45, sampleRate)) { index ->
            (1.8 * sin(2.0 * PI * 440.0 * index / sampleRate)).coerceIn(-1.0, 1.0).toFloat()
        }
        val clipped = runSignal(newEngine(sampleRate), clippedSignal)
        val cleanConfidence = assertNotNull(clean.lastOrNull()).confidence

        assertTrue(
            clipped.all { it == null || (it.clipped && it.confidence <= cleanConfidence * 0.5) },
            "clipping must be rejected or marked with materially degraded confidence",
        )
    }

    @Test
    fun `integrated confidence is finite normalized and readings carry note metrics`() {
        val readings = runSignal(
            newEngine(48_000),
            continuousSine(440.0, 48_000, seconds = 0.40, amplitude = 0.70),
        ).filterNotNull()

        assertTrue(readings.isNotEmpty())
        readings.forEach { reading ->
            assertTrue(reading.confidence.isFinite() && reading.confidence in 0.0..1.0)
            assertTrue(reading.rms.isFinite() && reading.rms > 0.0)
            assertEquals("A", reading.noteName)
            assertEquals(4, reading.octave)
            assertTrue(abs(reading.cents) <= 3.0)
            assertTrue(reading.isTuned)
            assertTrue(!reading.clipped)
        }
    }

    @Test
    fun `tuner reading exposes immutable value fields`() {
        val requiredFields = setOf(
            "noteName", "octave", "frequencyHz", "cents", "confidence", "isTuned", "rms", "clipped",
        )
        val fields = TunerReading::class.java.declaredFields.associateBy { it.name }

        requiredFields.forEach { name ->
            val field = assertNotNull(fields[name], "missing TunerReading.$name")
            assertTrue(Modifier.isFinal(field.modifiers), "TunerReading.$name must be immutable")
        }
    }

    @Test
    fun `reset discards partial framing detector noise and tracker history`() {
        val sampleRate = 48_000
        val engine = newEngine(sampleRate)
        val oldSignal = continuousSine(440.0, sampleRate, seconds = 0.45, amplitude = 0.70)
        val beforeReset = runSignal(engine, oldSignal)
        assertTrue(beforeReset.any { it != null })

        engine.reset()
        val staleCallbacks = mutableListOf<TunerReading?>()
        engine.accept(FloatArray(FRAME_SIZE - 1), FRAME_SIZE - 1, staleCallbacks::add)
        engine.reset()
        engine.accept(floatArrayOf(0f), 1, staleCallbacks::add)
        assertTrue(staleCallbacks.isEmpty(), "reset must discard a partial frame")

        engine.reset()
        val fresh = runSignal(engine, continuousSine(660.0, sampleRate, seconds = 0.45, amplitude = 0.70))
        val first = assertNotNull(fresh.firstOrNull { it != null })
        assertTrue(abs(centsError(660.0, first.frequencyHz)) <= 8.0, "reset retained old pitch: $first")
    }

    private fun newEngine(sampleRate: Int) = TunerEngine(
        sampleRate = sampleRate,
        frameSize = FRAME_SIZE,
        hopSize = HOP_SIZE,
        minFrequencyHz = MIN_FREQUENCY_HZ,
        maxFrequencyHz = MAX_FREQUENCY_HZ,
    )

    private fun runSignal(
        engine: TunerEngine,
        signal: FloatArray,
        chunks: IntArray = intArrayOf(4096, 137, 1024, 511, 73),
        appendUnusedTail: Boolean = false,
    ): List<TunerReading?> {
        val readings = mutableListOf<TunerReading?>()
        var offset = 0
        var chunkIndex = 0
        while (offset < signal.size) {
            val count = minOf(chunks[chunkIndex % chunks.size], signal.size - offset)
            val tail = if (appendUnusedTail) 11 else 0
            val captureBuffer = FloatArray(count + tail) { 1234.5f }
            signal.copyInto(captureBuffer, endIndex = offset + count, startIndex = offset)
            engine.accept(captureBuffer, count, readings::add)
            offset += count
            chunkIndex++
        }
        return readings
    }

    private fun assertAccurateCoverage(
        callbacks: List<TunerReading?>,
        expectedHz: Double,
        toleranceCents: Double,
        sampleRate: Int,
        warmupSeconds: Double,
        minimumCoverage: Double,
        label: String,
    ) {
        val firstWarmFrame = callbacks.indices.firstOrNull { frameIndex ->
            FRAME_SIZE + frameIndex * HOP_SIZE >= samplesFor(warmupSeconds, sampleRate)
        } ?: callbacks.size
        val evaluated = callbacks.drop(firstWarmFrame)
        assertTrue(evaluated.isNotEmpty(), "$label has no evaluated frames")
        val accurate = evaluated.count { reading ->
            reading != null && abs(centsError(expectedHz, reading.frequencyHz)) <= toleranceCents
        }
        val coverage = accurate.toDouble() / evaluated.size
        assertTrue(
            coverage >= minimumCoverage,
            "$label: $accurate/${evaluated.size} accurate readings ($coverage), expected >= $minimumCoverage",
        )
    }

    private fun continuousSine(
        frequencyHz: Double,
        sampleRate: Int,
        seconds: Double,
        amplitude: Double,
    ): FloatArray = FloatArray(samplesFor(seconds, sampleRate)) { index ->
        (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
    }

    private fun continuousHarmonicMixture(
        fundamentalHz: Double,
        sampleRate: Int,
        seconds: Double,
        harmonicAmplitudes: List<Double>,
        phaseRadians: Double,
    ): FloatArray = FloatArray(samplesFor(seconds, sampleRate)) { sampleIndex ->
        harmonicAmplitudes.indices.sumOf { harmonicIndex ->
            val harmonic = harmonicIndex + 1
            harmonicAmplitudes[harmonicIndex] * sin(
                2.0 * PI * fundamentalHz * harmonic * sampleIndex / sampleRate + phaseRadians * harmonic,
            )
        }.toFloat()
    }

    private fun centsError(expectedHz: Double, actualHz: Double): Double =
        1200.0 * ln(actualHz / expectedHz) / ln(2.0)

    private fun samplesFor(seconds: Double, sampleRate: Int): Int = (seconds * sampleRate).toInt()

    private companion object {
        const val FRAME_SIZE = 4096
        const val HOP_SIZE = 1024
        const val MIN_FREQUENCY_HZ = 40.0
        const val MAX_FREQUENCY_HZ = 1500.0
        val SAMPLE_RATES = intArrayOf(44_100, 48_000)
    }
}
