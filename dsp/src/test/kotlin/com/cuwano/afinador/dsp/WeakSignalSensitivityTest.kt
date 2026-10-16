package com.cuwano.afinador.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract of the raised sensitivity scale.
 *
 * The five levels keep their names, order and default; what changed is where the
 * whole ladder sits. These tests fix the *behaviour* that matters to the player:
 * a level must hear a note at the level it advertises, the default level must do
 * it from a cold start, an unplugged electric guitar must be audible at the upper
 * half of the slider, and the most sensitive level must still refuse broadband
 * noise and mains hum.
 *
 * The engine never reports a note it has not accepted, so "silent" is checked as
 * "produced no reading at all", not only as "no live reading".
 */
class WeakSignalSensitivityTest {
    @Test
    fun `each level sustains a note at its own activation threshold and rejects one well below it`() {
        // The absolute activation threshold is the profile's own advertised
        // "how quiet may a note be" number, so it is the one level a player can
        // reason about. 5 % above it the note must be heard; at half of it the
        // engine must stay quiet instead of half-detecting a tone it cannot trust.
        SAMPLE_RATES.forEach { sampleRate ->
            PROFILES.forEach { profile ->
                val heard = runSignal(
                    profile,
                    sampleRate,
                    harmonicNote(82.41, sampleRate, seconds = 1.4, targetRms = profile.minimumNoteRms * 1.05),
                )
                assertTrue(
                    heard.any { it?.isLive == true },
                    "$profile did not hear a note at its own threshold " +
                        "(${profile.minimumNoteRms} raw RMS, ${round(dbfs(profile.minimumNoteRms))} dBFS) " +
                        "at $sampleRate Hz",
                )

                val quiet = runSignal(
                    profile,
                    sampleRate,
                    harmonicNote(82.41, sampleRate, seconds = 1.4, targetRms = profile.minimumNoteRms * 0.5),
                )
                assertEquals(
                    0,
                    quiet.count { it != null },
                    "$profile reported ${quiet.count { it != null }} readings for a note at half its " +
                        "threshold (${profile.minimumNoteRms * 0.5} raw RMS) at $sampleRate Hz",
                )
            }
        }
    }

    @Test
    fun `the default level hears a very quiet note from a cold start`() {
        // A fresh engine carries a startup ambient estimate. Before this change it
        // started at 0.010 raw RMS (-40 dBFS) and needed tens of seconds of quiet
        // to decay, so right after launch the tuner behaved as if the room were
        // loud and the level control had to be pushed to the top. The startup
        // estimate must no longer be an activation gate above the profile's own.
        SAMPLE_RATES.forEach { sampleRate ->
            val callbacks = runSignal(
                SensitivityProfile.ALTA,
                sampleRate,
                continuousSine(440.0, sampleRate, seconds = 1.4, amplitude = 0.001),
            )
            val firstLiveFrame = callbacks.indexOfFirst { it?.isLive == true }

            assertTrue(firstLiveFrame >= 0, "the very quiet note never became live at $sampleRate Hz")
            val firstLiveEndSample = FRAME_SIZE + firstLiveFrame * HOP_SIZE
            assertTrue(
                firstLiveEndSample <= samplesFor(1.0, sampleRate) + FRAME_SIZE,
                "the very quiet note took $firstLiveEndSample samples to become live at $sampleRate Hz: " +
                    "a cold start must not gate it out while the startup estimate decays",
            )
        }
    }

    @Test
    fun `an unplugged electric guitar is audible at alta and muy alta and nothing quieter below`() {
        // Around -65 dBFS is what an unplugged electric guitar reaches at a phone
        // held next to the string: audible acoustically, far below a played acoustic
        // instrument. It must be the upper half of the slider that covers it.
        SAMPLE_RATES.forEach { sampleRate ->
            PROFILES.forEach { profile ->
                val readings = runSignal(
                    profile,
                    sampleRate,
                    harmonicNote(196.0, sampleRate, seconds = 1.4, targetRms = UNPLUGGED_GUITAR_RMS),
                )
                val live = readings.count { it?.isLive == true }
                if (profile == SensitivityProfile.ALTA || profile == SensitivityProfile.MUY_ALTA) {
                    assertTrue(
                        live > 0,
                        "$profile did not hear an unplugged electric guitar level note " +
                            "($UNPLUGGED_GUITAR_RMS raw RMS, ${round(dbfs(UNPLUGGED_GUITAR_RMS))} dBFS) at " +
                            "$sampleRate Hz",
                    )
                } else {
                    assertEquals(
                        0,
                        readings.count { it != null },
                        "$profile reported ${readings.count { it != null }} readings for an unplugged " +
                            "electric guitar level note ($UNPLUGGED_GUITAR_RMS raw RMS) at $sampleRate Hz",
                    )
                }
            }
        }
    }

    @Test
    fun `the most sensitive level still refuses broadband noise and mains hum`() {
        // Raising the ladder must not turn the top level into a noise detector. A
        // broadband room and a nearly pure mains hum carry no reliable pitch, so
        // neither may produce a reading.
        SAMPLE_RATES.forEach { sampleRate ->
            PROFILES.forEach { profile ->
                val noise = runSignal(
                    profile,
                    sampleRate,
                    SignalFixtures.whiteNoise(samplesFor(2.0, sampleRate), amplitude = 0.0012, seed = 0x5EEDL),
                )
                assertEquals(
                    0,
                    noise.count { it != null },
                    "$profile reported ${noise.count { it != null }} readings from broadband noise at " +
                        "$sampleRate Hz",
                )

                val hum = runSignal(profile, sampleRate, continuousSine(60.0, sampleRate, seconds = 2.0, amplitude = 0.02))
                assertEquals(
                    0,
                    hum.count { it != null },
                    "$profile reported ${hum.count { it != null }} readings from a 60 Hz hum at $sampleRate Hz",
                )
            }
        }
    }

    private fun harmonicNote(
        fundamentalHz: Double,
        sampleRate: Int,
        seconds: Double,
        targetRms: Double,
    ): FloatArray = scaledToRms(
        FloatArray(samplesFor(seconds, sampleRate)) { sampleIndex ->
            HARMONIC_AMPLITUDES.indices.sumOf { harmonicIndex ->
                val harmonic = harmonicIndex + 1
                HARMONIC_AMPLITUDES[harmonicIndex] *
                    sin(2.0 * PI * fundamentalHz * harmonic * sampleIndex / sampleRate)
            }.toFloat()
        },
        targetRms,
    )

    /** Replays a signal through a fresh engine and collects every published reading. */
    private fun runSignal(
        profile: SensitivityProfile,
        sampleRate: Int,
        signal: FloatArray,
    ): List<TunerReading?> {
        val engine = TunerEngine(
            sampleRate = sampleRate,
            frameSize = FRAME_SIZE,
            hopSize = HOP_SIZE,
            minFrequencyHz = MIN_FREQUENCY_HZ,
            maxFrequencyHz = MAX_FREQUENCY_HZ,
            profile = profile,
        )
        val readings = mutableListOf<TunerReading?>()
        var offset = 0
        while (offset < signal.size) {
            val count = minOf(1024, signal.size - offset)
            val buffer = FloatArray(count)
            signal.copyInto(buffer, startIndex = offset, endIndex = offset + count)
            engine.accept(buffer, count, readings::add)
            offset += count
        }
        return readings
    }

    private fun continuousSine(
        frequencyHz: Double,
        sampleRate: Int,
        seconds: Double,
        amplitude: Double,
    ): FloatArray = FloatArray(samplesFor(seconds, sampleRate)) { index ->
        (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
    }

    private fun scaledToRms(signal: FloatArray, targetRms: Double): FloatArray {
        var squaredSum = 0.0
        for (sample in signal) squaredSum += sample.toDouble() * sample
        val rms = sqrt(squaredSum / signal.size)
        require(rms > 0.0) { "fixture must carry energy" }
        val scale = targetRms / rms
        return FloatArray(signal.size) { (signal[it] * scale).toFloat() }
    }

    private fun dbfs(rms: Double): Double = 20.0 * log10(rms)

    private fun round(value: Double): Double = Math.round(value * 10.0) / 10.0

    private fun samplesFor(seconds: Double, sampleRate: Int): Int = (seconds * sampleRate).toInt()

    private companion object {
        const val FRAME_SIZE = 4096
        const val HOP_SIZE = 1024
        const val MIN_FREQUENCY_HZ = 40.0
        const val MAX_FREQUENCY_HZ = 1500.0

        /** Fundamental plus 2f/3f/4f: what a plucked string carries. */
        val HARMONIC_AMPLITUDES = listOf(1.0, 0.5, 0.33, 0.25)

        /** Raw RMS of the unplugged electric guitar fixture: about -65.2 dBFS. */
        const val UNPLUGGED_GUITAR_RMS = 0.00055

        val SAMPLE_RATES = intArrayOf(44_100, 48_000)

        /** Least to most sensitive; the whole ladder is exercised. */
        val PROFILES = listOf(
            SensitivityProfile.MUY_SUAVE,
            SensitivityProfile.SUAVE,
            SensitivityProfile.NORMAL,
            SensitivityProfile.ALTA,
            SensitivityProfile.MUY_ALTA,
        )
    }
}
