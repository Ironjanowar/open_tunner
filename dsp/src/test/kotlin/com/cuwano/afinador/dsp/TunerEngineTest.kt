package com.cuwano.afinador.dsp

import java.lang.reflect.Modifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `a tracked note turns stale with isLive false inside the stale window of silence input`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val silenceStartSample = FRAME_SIZE + 12 * HOP_SIZE
            val signal = FloatArray(silenceStartSample + samplesFor(1.0, sampleRate)) { index ->
                if (index < silenceStartSample) {
                    (0.70 * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
                } else {
                    0f
                }
            }
            val callbacks = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), signal)
            val firstFrameEndingAfterSilence =
                ((silenceStartSample - FRAME_SIZE + HOP_SIZE - 1) / HOP_SIZE).coerceAtLeast(0)
            assertTrue(
                callbacks.take(firstFrameEndingAfterSilence).any { it?.isLive == true },
                "note was never acquired live at $sampleRate Hz",
            )

            // The engine no longer clears the reading: it keeps the last note
            // and flips isLive to false once STALE_AFTER_SECONDS of silence
            // have elapsed since the last acceptable evidence.
            val staleIndex = (firstFrameEndingAfterSilence until callbacks.size)
                .firstOrNull { callbacks[it]?.isLive == false }
            val resolvedStaleIndex = assertNotNull(staleIndex, "note never turned stale at $sampleRate Hz")
            assertFalse(
                assertNotNull(callbacks[resolvedStaleIndex]).isTuned,
                "a stale note must not be reported as tuned at $sampleRate Hz",
            )
            val staleEndSample = FRAME_SIZE + resolvedStaleIndex * HOP_SIZE
            // 0.6 s of staleness plus one frame of overlap latency (the last
            // reliable frame still contained the dying tone).
            assertTrue(
                staleEndSample - silenceStartSample <= samplesFor(0.6, sampleRate) + FRAME_SIZE + HOP_SIZE,
                "stale flag took ${staleEndSample - silenceStartSample} samples at $sampleRate Hz",
            )
            assertTrue(
                callbacks.drop(resolvedStaleIndex).all { it != null },
                "the held note vanished instead of staying on screen at $sampleRate Hz",
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

    @Test
    fun `a quiet low-frequency ambient hum never produces a reading at any level`() {
        // A continuous tonal hum is not a played note. Measured with the previous
        // release the engine boosted the hum before measuring its level, so the SNR
        // gate opened on its own and notes appeared out of nowhere. Level
        // comparisons must happen in the raw domain, before normalisation.
        val humFrequencies = listOf(50.0, 60.0, 100.0, 120.0)
        val humAmplitudes = listOf(0.002, 0.006, 0.012, 0.02)
        SAMPLE_RATES.forEach { sampleRate ->
            humFrequencies.forEach { frequencyHz ->
                humAmplitudes.forEach { amplitude ->
                    PROFILES.forEach { profile ->
                        val callbacks = runSignal(
                            newEngine(sampleRate, profile),
                            continuousSine(frequencyHz, sampleRate, seconds = 2.0, amplitude = amplitude),
                        )
                        val emitted = callbacks.count { it != null }

                        assertTrue(
                            emitted == 0,
                            "a $frequencyHz Hz hum at amplitude $amplitude produced $emitted/" +
                                "${callbacks.size} readings at $profile / $sampleRate Hz " +
                                "(first=${callbacks.firstOrNull { it != null }})",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a quiet note is detected live only at alta and muy alta across every level`() {
        SAMPLE_RATES.forEach { sampleRate ->
            // amplitude 0.01 is an RMS of ~0.00707, about -43.0 dBFS: audible but far
            // below a played-out note. The two most sensitive levels must still hear
            // it and the three less sensitive ones must ignore it, so the level
            // control actually changes what the engine accepts.
            val quiet = continuousSine(440.0, sampleRate, seconds = 0.60, amplitude = QUIET_NOTE_AMPLITUDE)
            PROFILES.forEach { profile ->
                val live = runSignal(newEngine(sampleRate, profile), quiet)
                    .filterNotNull()
                    .filter { it.isLive }
                val mustDetect = profile == SensitivityProfile.ALTA || profile == SensitivityProfile.MUY_ALTA

                if (mustDetect) {
                    assertTrue(
                        live.isNotEmpty(),
                        "a quiet 440 Hz note (amplitude $QUIET_NOTE_AMPLITUDE, RMS $QUIET_NOTE_RMS, " +
                            "$QUIET_NOTE_DBFS dBFS) was never detected live at $profile / $sampleRate Hz",
                    )
                    live.forEach { reading ->
                        assertEquals("A", reading.noteName, "quiet note at $profile / $sampleRate Hz")
                        assertEquals(4, reading.octave, "quiet note at $profile / $sampleRate Hz")
                        assertTrue(
                            abs(centsError(440.0, reading.frequencyHz)) <= 5.0,
                            "quiet note at $profile / $sampleRate Hz read ${reading.frequencyHz} Hz",
                        )
                    }
                } else {
                    assertTrue(
                        live.isEmpty(),
                        "the less sensitive $profile / $sampleRate Hz must ignore a quiet 440 Hz note " +
                            "but reported ${live.size} live readings",
                    )
                }
            }
        }
    }

    @Test
    fun `a quiet sustained note becomes live within about a second in a quiet room`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val callbacks = runSignal(
                newEngine(sampleRate, SensitivityProfile.ALTA),
                continuousSine(440.0, sampleRate, seconds = 1.4, amplitude = QUIET_NOTE_AMPLITUDE),
            )

            val firstLiveFrame = callbacks.indexOfFirst { it?.isLive == true }
            assertTrue(
                firstLiveFrame >= 0,
                "the quiet note never became live at $sampleRate Hz",
            )
            val firstLiveEndSample = FRAME_SIZE + firstLiveFrame * HOP_SIZE

            assertTrue(
                firstLiveEndSample <= samplesFor(1.0, sampleRate) + FRAME_SIZE,
                "the quiet note took $firstLiveEndSample samples to become live at $sampleRate Hz: " +
                    "the floor must calibrate down quickly in a quiet room",
            )
        }
    }

    @Test
    fun `a long sustained note never drags the noise floor up to the note level`() {
        val sampleRate = 48_000
        val engine = newEngine(sampleRate, SensitivityProfile.ALTA)
        val loud = continuousSine(440.0, sampleRate, seconds = 6.0, amplitude = 0.70)
        val loudCallbacks = runSignal(engine, loud)

        val finalSecondFirstIndex = (0 until loudCallbacks.size)
            .first { FRAME_SIZE + it * HOP_SIZE >= loud.size - sampleRate }
        assertTrue(
            loudCallbacks.drop(finalSecondFirstIndex).all { it?.isLive == true },
            "the sustained note stopped being live in its final second at $sampleRate Hz",
        )

        // A quieter note straight afterwards must still be heard: were the floor
        // chasing the loud note, the gate would sit above this level for a long time.
        val quieter = runSignal(engine, continuousSine(440.0, sampleRate, seconds = 0.60, amplitude = 0.05))
        assertTrue(
            quieter.any { it?.isLive == true && abs(centsError(440.0, it.frequencyHz)) <= 5.0 },
            "after a loud sustained note the quieter note was no longer detected",
        )
    }

    @Test
    fun `a less sensitive level raises the ambient floor on the next profile change`() {
        val sampleRate = 48_000
        val engine = newEngine(sampleRate, SensitivityProfile.MUY_ALTA)

        // Let the floor calibrate down in a really quiet room ...
        runSignal(engine, FloatArray(sampleRate * 8))
        // ... and confirm the sensitive level hears the quiet note.
        val beforeSwitch = runSignal(
            engine,
            continuousSine(440.0, sampleRate, seconds = 0.60, amplitude = QUIET_PROFILE_SWITCH_AMPLITUDE),
        )
        assertTrue(
            beforeSwitch.any { it?.isLive == true },
            "MUY_ALTA never detected the quiet note before the profile change",
        )

        // MUY_SUAVE's absolute floor sits far above the calibrated ambient level, so
        // the change must raise the effective floor immediately instead of waiting
        // for the next learning cycle.
        engine.setProfile(SensitivityProfile.MUY_SUAVE)
        val afterSwitch = runSignal(
            engine,
            continuousSine(440.0, sampleRate, seconds = 2.0, amplitude = QUIET_PROFILE_SWITCH_AMPLITUDE),
        )
        val settled = afterSwitch.dropWhile { it?.isLive == true }

        assertTrue(
            settled.isNotEmpty(),
            "the quiet note stayed live after switching to MUY_SUAVE: the floor was not raised to the " +
                "new minimum right away",
        )
        assertTrue(
            settled.all { it != null && !it.isLive },
            "the quiet note was still reported live after switching to MUY_SUAVE",
        )
    }

    @Test
    fun `clipping is judged on the raw frame not on the normalised analysis buffer`() {
        val sampleRate = 48_000
        // Raw peak 0.9, below full scale, with a high crest factor: boosting the
        // frame toward the normaliser target pushes the analysis buffer past 1.0.
        // That must not be mistaken for a clipped capture and must not drop the note.
        val signal = FloatArray(samplesFor(0.5, sampleRate)) { index ->
            val tone = 0.05 * sin(2.0 * PI * 440.0 * index / sampleRate)
            if (index % FRAME_SIZE == 0) 0.9f else tone.toFloat()
        }
        val readings = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), signal).filterNotNull()

        assertTrue(readings.isNotEmpty(), "the quiet spike-laden A4 note was dropped")
        assertTrue(
            readings.none { it.clipped },
            "a raw frame whose peak is below full scale was reported as clipped",
        )
        val live = readings.filter { it.isLive }
        assertTrue(live.isNotEmpty(), "the spike-laden A4 note never became live")
        live.forEach { reading ->
            assertEquals("A", reading.noteName)
            assertEquals(4, reading.octave)
            assertTrue(abs(centsError(440.0, reading.frequencyHz)) <= 5.0, "read ${reading.frequencyHz} Hz")
        }
    }

    @Test
    fun `a clean sine is detected within three cents at every level`() {
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(110.0, 440.0).forEach { frequencyHz ->
                PROFILES.forEach { profile ->
                    val signal = continuousSine(frequencyHz, sampleRate, seconds = 0.55, amplitude = 0.70)
                    assertAccurateCoverage(
                        callbacks = runSignal(newEngine(sampleRate, profile), signal),
                        expectedHz = frequencyHz,
                        toleranceCents = 3.0,
                        sampleRate = sampleRate,
                        warmupSeconds = 0.25,
                        minimumCoverage = 0.95,
                        label = "$frequencyHz Hz within 3 cents at $profile / $sampleRate Hz",
                    )
                }
            }
        }
    }

    @Test
    fun `silence never produces a note at any level`() {
        SAMPLE_RATES.forEach { sampleRate ->
            PROFILES.forEach { profile ->
                val callbacks = runSignal(newEngine(sampleRate, profile), FloatArray(sampleRate))

                assertTrue(callbacks.isNotEmpty(), "silence must still account for analyzed frames")
                assertTrue(
                    callbacks.all { it == null },
                    "silence produced a tuner reading at $profile / $sampleRate Hz before any note was tracked",
                )
            }
        }
    }

    @Test
    fun `seeded noise keeps false notes under one percent of frames at every level`() {
        SAMPLE_RATES.forEach { sampleRate ->
            PROFILES.forEach { profile ->
                val noise = SignalFixtures.whiteNoise(sampleRate * 2, amplitude = 0.035, seed = 0x51A1E5L)
                val callbacks = runSignal(
                    newEngine(sampleRate, profile),
                    noise,
                    chunks = intArrayOf(137, 509, 31, 2048, 7),
                )
                val falseNotes = callbacks.count { it != null }
                val rate = falseNotes.toDouble() / callbacks.size

                assertTrue(
                    rate < 0.01,
                    "seeded noise produced $falseNotes/${callbacks.size} notes ($rate) at $profile / $sampleRate Hz",
                )
            }
        }
    }

    @Test
    fun `exact range boundaries are still detected at every level`() {
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ).forEach { frequencyHz ->
                PROFILES.forEach { profile ->
                    val readings = runSignal(
                        newEngine(sampleRate, profile),
                        continuousSine(frequencyHz, sampleRate, seconds = 0.50, amplitude = 0.70),
                    ).filterNotNull()

                    assertTrue(
                        readings.isNotEmpty(),
                        "$frequencyHz Hz at $profile / $sampleRate Hz is inside the range but was never detected",
                    )
                    val accurate = readings.count { abs(centsError(frequencyHz, it.frequencyHz)) <= 5.0 }
                    val coverage = accurate.toDouble() / readings.size
                    assertTrue(
                        coverage >= 0.9,
                        "$frequencyHz Hz at $profile / $sampleRate Hz: only $accurate/${readings.size} " +
                            "boundary readings within 5 cents ($coverage)",
                    )
                }
            }
        }
    }

    @Test
    fun `tones just outside the range still abstain at every level`() {
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(30.0, 1700.0).forEach { frequencyHz ->
                PROFILES.forEach { profile ->
                    val readings = runSignal(
                        newEngine(sampleRate, profile),
                        continuousSine(frequencyHz, sampleRate, seconds = 0.45, amplitude = 0.70),
                    ).filterNotNull()

                    assertTrue(
                        readings.isEmpty(),
                        "$frequencyHz Hz at $profile / $sampleRate Hz must abstain but produced " +
                            "${readings.size} readings, first=${readings.firstOrNull()}",
                    )
                }
            }
        }
    }

    @Test
    fun `a tracked note is held with isLive false and keeps its frequency through silence`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val silenceStartSample = FRAME_SIZE + 12 * HOP_SIZE
            val signal = FloatArray(silenceStartSample + samplesFor(1.0, sampleRate)) { index ->
                if (index < silenceStartSample) {
                    (0.70 * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
                } else {
                    0f
                }
            }
            val callbacks = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), signal)

            val liveIndices = callbacks.withIndex().filter { it.value?.isLive == true }.map { it.index }
            assertTrue(liveIndices.isNotEmpty(), "no live reading during the tone at $sampleRate Hz")
            val lastLive = assertNotNull(callbacks[liveIndices.last()])
            assertTrue(
                abs(centsError(440.0, lastLive.frequencyHz)) <= 5.0,
                "last live note was ${lastLive.frequencyHz} Hz at $sampleRate Hz",
            )

            // Once tracked, the engine must never return null again until reset.
            assertTrue(
                callbacks.drop(liveIndices.first()).all { it != null },
                "the engine returned null after the note was acquired at $sampleRate Hz",
            )

            val held = assertNotNull(callbacks.last(), "note was not held after silence at $sampleRate Hz")
            assertFalse(held.isLive, "held note must be stale at $sampleRate Hz")
            assertFalse(held.isTuned, "a stale note must not be reported as tuned at $sampleRate Hz")
            assertEquals(lastLive.noteName, held.noteName, "held note changed at $sampleRate Hz")
            assertEquals(lastLive.octave, held.octave, "held octave changed at $sampleRate Hz")
            assertEquals(lastLive.frequencyHz, held.frequencyHz, 1e-9, "held frequency drifted at $sampleRate Hz")

            val staleReadings = callbacks.drop(liveIndices.last() + 1).filterNotNull()
            assertTrue(staleReadings.isNotEmpty(), "no stale frames after the grace window at $sampleRate Hz")
            assertTrue(
                staleReadings.all { it.noteName == lastLive.noteName && it.octave == lastLive.octave },
                "the displayed note changed while stale at $sampleRate Hz",
            )
            assertTrue(
                staleReadings.all { !it.isTuned },
                "stale frames reported isTuned=true at $sampleRate Hz",
            )
            assertTrue(
                staleReadings.all { !it.isLive },
                "a stale frame was still flagged live at $sampleRate Hz",
            )
        }
    }

    @Test
    fun `a new note replaces the held note and becomes live again`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val firstToneEnd = FRAME_SIZE + 12 * HOP_SIZE
            val newToneStart = firstToneEnd + samplesFor(1.0, sampleRate)
            val total = newToneStart + samplesFor(0.55, sampleRate)
            val signal = FloatArray(total) { index ->
                when {
                    index < firstToneEnd -> (0.70 * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
                    index < newToneStart -> 0f
                    else -> (0.70 * sin(2.0 * PI * 660.0 * index / sampleRate)).toFloat()
                }
            }
            val callbacks = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), signal)

            val firstLive = callbacks.firstOrNull { it?.isLive == true && abs(centsError(440.0, it.frequencyHz)) <= 5.0 }
            val acquired = assertNotNull(firstLive, "440 Hz never became live at $sampleRate Hz")

            val lastReading = assertNotNull(callbacks.last(), "no reading at the end at $sampleRate Hz")
            assertTrue(lastReading.isLive, "the replacement note never became live at $sampleRate Hz")
            assertTrue(
                abs(centsError(660.0, lastReading.frequencyHz)) <= 8.0,
                "final note was ${lastReading.frequencyHz} Hz, expected 660 at $sampleRate Hz",
            )
            assertTrue(
                acquired.noteName != lastReading.noteName || acquired.octave != lastReading.octave,
                "at $sampleRate Hz the new 660 Hz note did not replace ${acquired.noteName}${acquired.octave}",
            )
        }
    }

    @Test
    fun `a tracked note is still held after several seconds of silence`() {
        SAMPLE_RATES.forEach { sampleRate ->
            val silenceStartSample = FRAME_SIZE + 12 * HOP_SIZE
            val signal = FloatArray(silenceStartSample + samplesFor(3.0, sampleRate)) { index ->
                if (index < silenceStartSample) {
                    (0.70 * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
                } else {
                    0f
                }
            }
            val callbacks = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), signal)

            val held = assertNotNull(callbacks.last(), "note was not held after several seconds at $sampleRate Hz")
            assertFalse(held.isLive, "held note must be stale after several seconds at $sampleRate Hz")
            assertFalse(held.isTuned)
            assertEquals("A", held.noteName)
            assertEquals(4, held.octave)
            assertTrue(abs(centsError(440.0, held.frequencyHz)) <= 5.0)
        }
    }

    @Test
    fun `reset clears a held note and the engine abstains again`() {
        val sampleRate = 48_000
        val engine = newEngine(sampleRate, SensitivityProfile.ALTA)
        val silenceStart = FRAME_SIZE + 12 * HOP_SIZE
        val tone = FloatArray(silenceStart) { (0.70 * sin(2.0 * PI * 440.0 * it / sampleRate)).toFloat() }
        assertTrue(runSignal(engine, tone).any { it?.isLive == true }, "held note was never acquired")

        engine.reset()
        val afterReset = mutableListOf<TunerReading?>()
        engine.accept(FloatArray(FRAME_SIZE - 1), FRAME_SIZE - 1, afterReset::add)
        engine.reset()
        engine.accept(floatArrayOf(0f), 1, afterReset::add)
        assertTrue(afterReset.isEmpty(), "reset must discard the held note and any partial frame")

        engine.reset()
        val fresh = runSignal(engine, continuousSine(660.0, sampleRate, seconds = 0.45, amplitude = 0.70))
        val first = assertNotNull(fresh.firstOrNull { it != null })
        assertTrue(abs(centsError(660.0, first.frequencyHz)) <= 8.0, "reset retained the old held note: $first")
    }

    @Test
    fun `profile is readable and setProfile applies immediately`() {
        val engine = newEngine(48_000, SensitivityProfile.NORMAL)
        assertEquals(SensitivityProfile.NORMAL, engine.profile)

        engine.setProfile(SensitivityProfile.MUY_ALTA)
        assertEquals(SensitivityProfile.MUY_ALTA, engine.profile)
    }

    @Test
    fun `a soft harmonic rich low note is reported live and correct at alta`() {
        // Below LOW_BAND_LIMIT_HZ the current guard is a blunt raw-RMS floor
        // (LOW_BAND_MINIMUM_RMS = 0.020, about -34 dBFS), so the guitar's low
        // E/A/D and a bass low E all have to be played ~10.5 dB louder than ALTA's
        // own minimumNoteRms (0.006, -44.4 dBFS). A softly played harmonic-rich
        // string is a real note and must be reported at the profile's own level:
        // its harmonic structure, not its loudness, is what separates it from hum.
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(82.41, 110.0, 146.83).forEach { fundamentalHz ->
                val signal = harmonicLowNote(
                    fundamentalHz = fundamentalHz,
                    sampleRate = sampleRate,
                    seconds = 1.4,
                    targetRms = SOFT_LOW_NOTE_RMS,
                )
                val live = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), signal)
                    .filterNotNull()
                    .filter { it.isLive }

                assertTrue(
                    live.isNotEmpty(),
                    "a soft $fundamentalHz Hz harmonic note (raw RMS $SOFT_LOW_NOTE_RMS, " +
                        "${dbfs(SOFT_LOW_NOTE_RMS)} dBFS) was never reported live at ALTA / $sampleRate Hz: " +
                        "the low-band level floor still blocks it",
                )
                val expected = assertNotNull(NoteMapper.map(fundamentalHz))
                live.forEach { reading ->
                    assertEquals(
                        expected.noteName,
                        reading.noteName,
                        "soft $fundamentalHz Hz note at $sampleRate Hz",
                    )
                    assertEquals(
                        expected.octave,
                        reading.octave,
                        "soft $fundamentalHz Hz note at $sampleRate Hz",
                    )
                    assertTrue(
                        abs(centsError(fundamentalHz, reading.frequencyHz)) <= 5.0,
                        "soft $fundamentalHz Hz note read ${reading.frequencyHz} Hz at $sampleRate Hz",
                    )
                }
            }
        }
    }

    @Test
    fun `the sensitivity slider still changes whether a soft low note is heard`() {
        // Exact levels pinned: at raw RMS 0.0080 (about -41.9 dBFS) the soft
        // 82.41 Hz (E2) harmonic note is live at ALTA (minimumNoteRms 0.006) and
        // MUY_ALTA (0.003) and silent at NORMAL (0.012), SUAVE (0.020) and
        // MUY_SUAVE (0.030). Below 150 Hz the sensitivity slider must move the
        // detection level again, instead of every profile sharing one fixed floor.
        SAMPLE_RATES.forEach { sampleRate ->
            val signal = harmonicLowNote(
                fundamentalHz = 82.41,
                sampleRate = sampleRate,
                seconds = 1.4,
                targetRms = SOFT_LOW_NOTE_RMS,
            )
            PROFILES.forEach { profile ->
                val live = runSignal(newEngine(sampleRate, profile), signal)
                    .filterNotNull()
                    .filter { it.isLive }
                if (profile == SensitivityProfile.ALTA || profile == SensitivityProfile.MUY_ALTA) {
                    assertTrue(
                        live.isNotEmpty(),
                        "a soft 82.41 Hz harmonic note (raw RMS $SOFT_LOW_NOTE_RMS) must be live at " +
                            "$profile / $sampleRate Hz",
                    )
                } else {
                    assertTrue(
                        live.isEmpty(),
                        "a soft 82.41 Hz harmonic note (raw RMS $SOFT_LOW_NOTE_RMS) must stay silent at " +
                            "the less sensitive $profile / $sampleRate Hz but reported ${live.size} live readings",
                    )
                }
            }
        }
    }

    @Test
    fun `the low band separates a string from a pure sine at the same level`() {
        // Both fixtures carry an identical raw RMS. The harmonic-rich string must be
        // reported; the pure sine (mains hum) has no measurable energy at 2f/3f
        // relative to its fundamental and must be rejected. Because both are equally
        // loud, the rejection must come from harmonic structure and not from a level
        // floor.
        SAMPLE_RATES.forEach { sampleRate ->
            val harmonic = harmonicLowNote(82.41, sampleRate, seconds = 1.4, targetRms = SOFT_LOW_NOTE_RMS)
            val pure = scaledToRms(
                continuousSine(82.41, sampleRate, seconds = 1.4, amplitude = 1.0),
                SOFT_LOW_NOTE_RMS,
            )
            val liveHarmonic = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), harmonic)
                .filterNotNull()
                .filter { it.isLive }
            val livePure = runSignal(newEngine(sampleRate, SensitivityProfile.ALTA), pure)
                .filterNotNull()
                .filter { it.isLive }

            assertTrue(
                liveHarmonic.isNotEmpty(),
                "the harmonic-rich 82.41 Hz note must be live at ALTA / $sampleRate Hz",
            )
            assertTrue(
                livePure.isEmpty(),
                "a pure 82.41 Hz sine at the same raw RMS $SOFT_LOW_NOTE_RMS must be rejected as hum at " +
                    "ALTA / $sampleRate Hz but produced ${livePure.size} live readings",
            )
        }
    }

    @Test
    fun `a pure 60 hz hum above the level threshold is rejected by harmonic structure`() {
        // amplitude 0.012 -> raw RMS 0.00849, above ALTA's 0.006 and MUY_ALTA's 0.003
        // minimumNoteRms, so the per-profile level knob cannot be what rejects it: the
        // absence of 2f/3f energy is. Today the old 0.020 low-band level floor hides
        // this; the harmonic-structure check must keep it silent once that floor is gone.
        SAMPLE_RATES.forEach { sampleRate ->
            PROFILES.forEach { profile ->
                val callbacks = runSignal(
                    newEngine(sampleRate, profile),
                    continuousSine(60.0, sampleRate, seconds = 2.0, amplitude = 0.012),
                )
                val emitted = callbacks.count { it != null }

                assertTrue(
                    emitted == 0,
                    "a 60 Hz hum at amplitude 0.012 produced $emitted/${callbacks.size} readings at " +
                        "$profile / $sampleRate Hz (first=${callbacks.firstOrNull { it != null }})",
                )
            }
        }
    }

    @Test
    fun `a 60 hz hum at the old low band amplitude has no level cliff`() {
        // amplitude 0.020 -> raw RMS 0.01414, the loudest hum the hum corpus pins.
        // There must be no level at which a lone 60 Hz sine starts being reported:
        // once the 0.020 raw-RMS floor is replaced by a harmonic-structure check, hum
        // stays silent all the way up to the clearly-loud path reserved for real
        // played notes.
        SAMPLE_RATES.forEach { sampleRate ->
            PROFILES.forEach { profile ->
                val callbacks = runSignal(
                    newEngine(sampleRate, profile),
                    continuousSine(60.0, sampleRate, seconds = 2.0, amplitude = 0.020),
                )
                val emitted = callbacks.count { it != null }

                assertTrue(
                    emitted == 0,
                    "a 60 Hz hum at amplitude 0.020 produced $emitted/${callbacks.size} readings at " +
                        "$profile / $sampleRate Hz (first=${callbacks.firstOrNull { it != null }})",
                )
            }
        }
    }

    @Test
    fun `a loud pure sine below the low band limit is still detected within five cents`() {
        // The loud 40/80/110 Hz accuracy fixtures are pure sines with no harmonics.
        // A clearly-loud input must skip the harmonic-support requirement, or the
        // low-band check would start rejecting these factory fixtures.
        SAMPLE_RATES.forEach { sampleRate ->
            listOf(40.0, 80.0, 110.0).forEach { frequencyHz ->
                PROFILES.forEach { profile ->
                    val readings = runSignal(
                        newEngine(sampleRate, profile),
                        continuousSine(frequencyHz, sampleRate, seconds = 0.55, amplitude = 0.70),
                    ).filterNotNull()

                    assertTrue(
                        readings.isNotEmpty(),
                        "$frequencyHz Hz at 0.70 amplitude was never detected at $profile / $sampleRate Hz",
                    )
                    val accurate = readings.count { abs(centsError(frequencyHz, it.frequencyHz)) <= 5.0 }
                    val coverage = accurate.toDouble() / readings.size
                    assertTrue(
                        coverage >= 0.9,
                        "$frequencyHz Hz loud pure sine at $profile / $sampleRate Hz: only " +
                            "$accurate/${readings.size} readings within 5 cents ($coverage)",
                    )
                }
            }
        }
    }

    /**
     * Fundamental plus 2f/3f/4f harmonics, scaled to an exact raw RMS. A plucked
     * string carries harmonic energy above its fundamental; a mains hum carries none.
     */
    private fun harmonicLowNote(
        fundamentalHz: Double,
        sampleRate: Int,
        seconds: Double,
        targetRms: Double,
    ): FloatArray = scaledToRms(
        continuousHarmonicMixture(
            fundamentalHz = fundamentalHz,
            sampleRate = sampleRate,
            seconds = seconds,
            harmonicAmplitudes = LOW_NOTE_HARMONICS,
            phaseRadians = 0.0,
        ),
        targetRms,
    )

    /** Scales a fixture so its raw frame RMS matches [targetRms] exactly. */
    private fun scaledToRms(signal: FloatArray, targetRms: Double): FloatArray {
        var squaredSum = 0.0
        for (sample in signal) squaredSum += sample.toDouble() * sample
        val rms = sqrt(squaredSum / signal.size)
        require(rms > 0.0) { "fixture must carry energy" }
        val scale = targetRms / rms
        return FloatArray(signal.size) { (signal[it] * scale).toFloat() }
    }

    private fun dbfs(rms: Double): Double = 20.0 * log10(rms)

    private fun newEngine(
        sampleRate: Int,
        profile: SensitivityProfile = SensitivityProfile.DEFAULT,
    ) = TunerEngine(
        sampleRate = sampleRate,
        frameSize = FRAME_SIZE,
        hopSize = HOP_SIZE,
        minFrequencyHz = MIN_FREQUENCY_HZ,
        maxFrequencyHz = MAX_FREQUENCY_HZ,
        profile = profile,
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

        /** Sine amplitude of the "quiet note" fixtures. */
        const val QUIET_NOTE_AMPLITUDE = 0.01

        /** A sine of amplitude 0.01 has an RMS of 0.01 / sqrt(2) = 0.00707 (-43.0 dBFS). */
        const val QUIET_NOTE_RMS = 0.00707
        const val QUIET_NOTE_DBFS = -43.0

        /**
         * The profile-change fixture has to sit between the two levels' absolute
         * floors: loud enough for MUY_ALTA's floor, quiet enough that MUY_SUAVE's
         * floor (0.004) plus its 5x signal-to-noise ratio still rejects it.
         */
        const val QUIET_PROFILE_SWITCH_AMPLITUDE = 0.026

        val SAMPLE_RATES = intArrayOf(44_100, 48_000)

        /** Least to most sensitive; every level-gated test must run on all of them. */
        val PROFILES = listOf(
            SensitivityProfile.MUY_SUAVE,
            SensitivityProfile.SUAVE,
            SensitivityProfile.NORMAL,
            SensitivityProfile.ALTA,
            SensitivityProfile.MUY_ALTA,
        )

        /** Harmonic weights of the soft low-note fixtures: fundamental + 2f + 3f + 4f. */
        val LOW_NOTE_HARMONICS = listOf(1.0, 0.5, 0.33, 0.25)

        /**
         * Raw RMS of the soft low-note fixtures, about -41.9 dBFS. It sits above ALTA's
         * own `minimumNoteRms` (0.006, -44.4 dBFS) and below NORMAL's (0.012, -38.4 dBFS),
         * so a soft sub-150 Hz note at this level must be heard by the two most sensitive
         * levels and ignored by the other three. (A -45 dBFS note, as the review
         * mentions, sits below ALTA's own threshold and could never be live at ALTA.)
         */
        const val SOFT_LOW_NOTE_RMS = 0.0080
    }
}
