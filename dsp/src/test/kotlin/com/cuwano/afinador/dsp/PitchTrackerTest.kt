package com.cuwano.afinador.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PitchTrackerTest {
    @Test
    fun `strong agreeing observations produce a stable frequency cents and confidence`() {
        val tracker = newTracker()

        val reading = assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), elapsedSeconds = 0.02))

        assertEquals(440.0, reading.frequencyHz, absoluteTolerance = 1e-12)
        assertEquals(0.0, reading.cents, absoluteTolerance = 1e-9)
        assertTrue(reading.confidence in 0.8..1.0, "confidence was ${reading.confidence}")
    }

    @Test
    fun `log frequency smoothing follows cents rather than arithmetic hertz`() {
        val tracker = primedTrackerForSmoothing(halfLifeSeconds = 0.02)

        val reading = assertNotNull(tracker.update(strongFrame(primaryHz = 444.0), elapsedSeconds = 0.02))
        val expected = 440.0 * (444.0 / 440.0).pow(0.5)

        assertEquals(expected, reading.frequencyHz, absoluteTolerance = 1e-9)
        assertEquals(assertNotNull(NoteMapper.map(expected)).cents, reading.cents, absoluteTolerance = 1e-9)
    }

    @Test
    fun `time based log smoothing is cadence equivalent`() {
        val oneUpdate = primedTrackerForSmoothing(halfLifeSeconds = 0.08)
        val sixUpdates = primedTrackerForSmoothing(halfLifeSeconds = 0.08)

        val once = assertNotNull(oneUpdate.update(strongFrame(primaryHz = 444.0), elapsedSeconds = 0.12))
        var split = assertNotNull(sixUpdates.current)
        repeat(6) {
            split = assertNotNull(sixUpdates.update(strongFrame(primaryHz = 444.0), elapsedSeconds = 0.02))
        }

        val retained = 0.5.pow(0.12 / 0.08)
        val expected = 440.0 * (444.0 / 440.0).pow(1.0 - retained)
        assertEquals(expected, once.frequencyHz, absoluteTolerance = 1e-9)
        assertWithinCents(once.frequencyHz, split.frequencyHz, toleranceCents = 0.01)
    }

    @Test
    fun `short median stabilization rejects alternating pitch jitter`() {
        val tracker = newTracker(smoothingHalfLifeSeconds = 0.001, changeConfirmationSeconds = 0.0)
        assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), 0.10))
        assertNotNull(tracker.update(strongFrame(primaryHz = 441.0), 0.10))

        val stabilized = assertNotNull(tracker.update(strongFrame(primaryHz = 439.0), 0.10))

        assertWithinCents(440.0, stabilized.frequencyHz, toleranceCents = 0.25)
    }

    @Test
    fun `confidence ignores unavailable YIN rather than averaging in zero`() {
        val tracker = newTracker()

        val reading = assertNotNull(
            tracker.update(
                strongFrame(primaryHz = 440.0).copy(yinFrequencyHz = null, yinConfidence = 0.0),
                elapsedSeconds = 0.02,
            ),
        )

        assertEquals(0.90, reading.confidence, absoluteTolerance = 1e-12)
    }

    @Test
    fun `confidence ignores YIN when it is irrelevant to the selected primary`() {
        val tracker = newTracker()

        val reading = assertNotNull(
            tracker.update(
                strongFrame(primaryHz = 440.0, yinHz = 466.16, yinConfidence = 0.20),
                elapsedSeconds = 0.02,
            ),
        )

        assertEquals(0.90, reading.confidence, absoluteTolerance = 1e-12)
    }

    @Test
    fun `weak primary salience and a closed signal to noise gate do not acquire pitch`() {
        val tracker = newTracker()

        assertNull(
            tracker.update(
                strongFrame(primaryHz = 440.0).copy(primarySalience = 0.49),
                elapsedSeconds = 0.02,
            ),
        )
        assertNull(
            tracker.update(
                strongFrame(primaryHz = 440.0).copy(rms = 0.029, noiseRms = 0.010),
                elapsedSeconds = 0.02,
            ),
        )
    }

    @Test
    fun `strong clipped frame cannot acquire pitch`() {
        val tracker = newTracker()

        assertNull(
            tracker.update(
                strongFrame(primaryHz = 440.0).copy(isClipped = true),
                elapsedSeconds = 0.02,
            ),
        )
    }

    @Test
    fun `strong clipped frame cannot refresh an existing pitch`() {
        val tracker = newTracker(clearAfterSeconds = 0.30)
        assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), elapsedSeconds = 0.02))

        assertNotNull(
            tracker.update(
                strongFrame(primaryHz = 660.0).copy(isClipped = true),
                elapsedSeconds = 0.20,
            ),
        )
        assertNull(
            tracker.update(
                strongFrame(primaryHz = 660.0).copy(isClipped = true),
                elapsedSeconds = 0.11,
            ),
            "clipped frames must age, not refresh, the last reliable pitch",
        )
    }

    @Test
    fun `many rapid weak callbacks do not expire a reading before sustained weak time`() {
        val tracker = newTracker(clearAfterSeconds = 0.30)
        assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), elapsedSeconds = 0.02))

        repeat(100) {
            assertNotNull(tracker.update(weakFrame(), elapsedSeconds = 0.001))
        }
    }

    @Test
    fun `weak elapsed audio time clears a stale reading independent of callback count`() {
        val tracker = newTracker(clearAfterSeconds = 0.30)
        assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), elapsedSeconds = 0.02))

        assertNotNull(tracker.update(weakFrame(), elapsedSeconds = 0.20))
        assertNull(tracker.update(weakFrame(), elapsedSeconds = 0.11))
    }

    @Test
    fun `one isolated octave spike is rejected`() {
        val tracker = newTracker(smoothingHalfLifeSeconds = 0.04, changeConfirmationSeconds = 0.12)
        repeat(4) { assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), 0.02)) }

        val spike = assertNotNull(tracker.update(strongFrame(primaryHz = 880.0), 0.02))
        val recovered = assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), 0.02))

        assertWithinCents(440.0, spike.frequencyHz, toleranceCents = 8.0)
        assertWithinCents(440.0, recovered.frequencyHz, toleranceCents = 8.0)
    }

    @Test
    fun `persistent real octave change is accepted within three hundred milliseconds`() {
        assertPersistentChangeAccepted(targetHz = 880.0)
    }

    @Test
    fun `persistent real semitone change is accepted within three hundred milliseconds`() {
        assertPersistentChangeAccepted(targetHz = 440.0 * 2.0.pow(1.0 / 12.0))
    }

    @Test
    fun `persistent glissando motion follows the moving pitch instead of latching`() {
        val tracker = newTracker(smoothingHalfLifeSeconds = 0.04, changeConfirmationSeconds = 0.12)
        repeat(4) { assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), 0.02)) }

        val frequencies = (1..12).map { step -> 440.0 * 2.0.pow(step * 60.0 / 1200.0) }
        var reading = assertNotNull(tracker.current)
        frequencies.forEach { frequencyHz ->
            reading = assertNotNull(tracker.update(strongFrame(primaryHz = frequencyHz), 0.02))
        }

        assertTrue(
            reading.frequencyHz > 440.0 * 2.0.pow(400.0 / 1200.0),
            "tracker latched at ${reading.frequencyHz} Hz during a 720-cent glissando",
        )
        assertWithinCents(frequencies.last(), reading.frequencyHz, toleranceCents = 180.0)
    }

    @Test
    fun `high confidence YIN corrects an octave error from primary salience`() {
        val tracker = newTracker()

        val corrected = assertNotNull(
            tracker.update(
                strongFrame(primaryHz = 220.0, yinHz = 110.0, yinConfidence = 0.95),
                elapsedSeconds = 0.02,
            ),
        )

        assertEquals(110.0, corrected.frequencyHz, absoluteTolerance = 1e-12)
    }

    @Test
    fun `low confidence YIN cannot override the primary salience octave`() {
        val tracker = newTracker()

        val reading = assertNotNull(
            tracker.update(
                strongFrame(primaryHz = 220.0, yinHz = 110.0, yinConfidence = 0.79),
                elapsedSeconds = 0.02,
            ),
        )

        assertEquals(220.0, reading.frequencyHz, absoluteTolerance = 1e-12)
    }

    @Test
    fun `high confidence YIN only overrides primary for octave related disagreement`() {
        val tracker = newTracker()

        val reading = assertNotNull(
            tracker.update(
                strongFrame(primaryHz = 220.0, yinHz = 147.0, yinConfidence = 0.95),
                elapsedSeconds = 0.02,
            ),
        )

        assertEquals(220.0, reading.frequencyHz, absoluteTolerance = 1e-12)
    }

    @Test
    fun `visual tuned state enters at five cents and exits only beyond eight cents`() {
        val tracker = newTracker(smoothingHalfLifeSeconds = 1e-9, changeConfirmationSeconds = 0.0)

        assertTrue(assertNotNull(tracker.update(strongFrame(atCents(4.0)), 0.02)).isTuned)
        assertTrue(assertNotNull(tracker.update(strongFrame(atCents(7.0)), 0.02)).isTuned)
        assertTrue(assertNotNull(tracker.update(strongFrame(atCents(8.0)), 0.02)).isTuned)
        assertFalse(assertNotNull(tracker.update(strongFrame(atCents(8.01)), 0.02)).isTuned)
        assertFalse(assertNotNull(tracker.update(strongFrame(atCents(6.0)), 0.02)).isTuned)
        assertTrue(assertNotNull(tracker.update(strongFrame(atCents(5.0)), 0.02)).isTuned)
    }

    @Test
    fun `reset immediately clears pitch smoothing weak history and tuned state`() {
        val tracker = newTracker()
        assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), elapsedSeconds = 0.02))
        tracker.reset()

        assertNull(tracker.current)
        val fresh = assertNotNull(tracker.update(strongFrame(primaryHz = 444.0, yinHz = 444.0), 0.02))
        assertEquals(444.0, fresh.frequencyHz, absoluteTolerance = 1e-12)
    }

    private fun newTracker(
        clearAfterSeconds: Double = 0.30,
        smoothingHalfLifeSeconds: Double = 0.08,
        changeConfirmationSeconds: Double = 0.12,
    ) = PitchTracker(
        minimumPrimarySalience = 0.50,
        minimumYinConfidenceForOctaveCorrection = 0.80,
        minimumSignalToNoiseRatio = 3.0,
        smoothingHalfLifeSeconds = smoothingHalfLifeSeconds,
        changeConfirmationSeconds = changeConfirmationSeconds,
        clearAfterSeconds = clearAfterSeconds,
        tunedEnterCents = 5.0,
        tunedExitCents = 8.0,
    )

    private fun primedTrackerForSmoothing(halfLifeSeconds: Double): PitchTracker {
        val tracker = newTracker(
            smoothingHalfLifeSeconds = halfLifeSeconds,
            changeConfirmationSeconds = 0.0,
        )
        assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), elapsedSeconds = 0.02))
        repeat(2) {
            assertNotNull(tracker.update(strongFrame(primaryHz = 444.0), elapsedSeconds = 0.0))
        }
        return tracker
    }

    private fun assertPersistentChangeAccepted(targetHz: Double) {
        val tracker = newTracker(smoothingHalfLifeSeconds = 0.04, changeConfirmationSeconds = 0.12)
        repeat(4) { assertNotNull(tracker.update(strongFrame(primaryHz = 440.0), 0.02)) }

        var acceptedAtSeconds: Double? = null
        repeat(15) { index ->
            val reading = assertNotNull(tracker.update(strongFrame(primaryHz = targetHz), 0.02))
            if (acceptedAtSeconds == null && centsBetween(targetHz, reading.frequencyHz) <= 8.0) {
                acceptedAtSeconds = (index + 1) * 0.02
            }
        }

        assertNotNull(acceptedAtSeconds, "$targetHz Hz change was never accepted")
        assertTrue(acceptedAtSeconds <= 0.30, "$targetHz Hz accepted after $acceptedAtSeconds seconds")
    }

    private fun strongFrame(
        primaryHz: Double,
        yinHz: Double = primaryHz,
        yinConfidence: Double = 0.95,
    ) = PitchTrackerFrame(
        primaryFrequencyHz = primaryHz,
        primarySalience = 0.90,
        yinFrequencyHz = yinHz,
        yinConfidence = yinConfidence,
        rms = 0.080,
        noiseRms = 0.010,
    )

    private fun weakFrame() = PitchTrackerFrame(
        primaryFrequencyHz = null,
        primarySalience = 0.0,
        yinFrequencyHz = null,
        yinConfidence = 0.0,
        rms = 0.005,
        noiseRms = 0.010,
    )

    private fun atCents(cents: Double): Double = 440.0 * 2.0.pow(cents / 1200.0)

    private fun assertWithinCents(expectedHz: Double, actualHz: Double, toleranceCents: Double) {
        val error = centsBetween(expectedHz, actualHz)
        assertTrue(error <= toleranceCents, "expected $expectedHz Hz, got $actualHz Hz ($error cents)")
    }

    private fun centsBetween(firstHz: Double, secondHz: Double): Double =
        abs(1200.0 * ln(secondHz / firstHz) / ln(2.0))
}
