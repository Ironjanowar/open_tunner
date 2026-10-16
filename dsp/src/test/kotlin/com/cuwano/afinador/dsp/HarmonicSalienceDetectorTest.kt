package com.cuwano.afinador.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarmonicSalienceDetectorTest {
    private val sampleRate = 48_000
    private val frameSize = 4096
    private val detector = HarmonicSalienceDetector(
        frameSize = frameSize,
        minFrequencyHz = 40.0,
        maxFrequencyHz = 1500.0,
    )

    @Test
    fun `proposes low mid and high fundamentals from harmonic frames`() {
        val cases = listOf(
            55.0 to 12.0,
            110.0 to 10.0,
            440.0 to 8.0,
            1000.0 to 8.0,
        )

        cases.forEach { (expectedHz, toleranceCents) ->
            val frame = SignalFixtures.harmonicMixture(
                fundamentalHz = expectedHz,
                sampleRate = sampleRate,
                size = frameSize,
                harmonicAmplitudes = listOf(0.72, 0.48, 0.31, 0.19, 0.11),
                phaseRadians = 0.37,
            )

            val proposal = assertNotNull(detector.propose(frame, sampleRate), "$expectedHz Hz")

            assertWithinCents(expectedHz, proposal.frequencyHz, toleranceCents)
            assertTrue(proposal.salience.isFinite() && proposal.salience > 0.0)
        }
    }

    @Test
    fun `pure sine frames remain valid primary pitch proposals`() {
        listOf(440.0, 1000.0).forEach { expectedHz ->
            val frame = SignalFixtures.sine(
                frequencyHz = expectedHz,
                sampleRate = sampleRate,
                size = frameSize,
                amplitude = 0.8,
                phaseRadians = 0.61,
            )

            val proposal = assertNotNull(detector.propose(frame, sampleRate), "$expectedHz Hz")

            assertWithinCents(expectedHz, proposal.frequencyHz, toleranceCents = 10.0)
        }
    }

    @Test
    fun `missing fundamental is recovered from upper harmonics rather than octave doubled`() {
        val expectedHz = 110.0
        val frame = SignalFixtures.harmonicMixture(
            fundamentalHz = expectedHz,
            sampleRate = sampleRate,
            size = frameSize,
            harmonicAmplitudes = listOf(0.0, 0.88, 0.62, 0.38, 0.22, 0.12),
            phaseRadians = 0.23,
        )

        val proposal = assertNotNull(detector.propose(frame, sampleRate))

        assertWithinCents(expectedHz, proposal.frequencyHz, toleranceCents = 10.0)
        assertTrue(abs(centsError(expectedHz * 2.0, proposal.frequencyHz)) > 900.0)
    }

    @Test
    fun `dominant harmonic survives a fixed interferer and seeded broadband noise`() {
        val expectedHz = 440.0
        val pitched = SignalFixtures.harmonicMixture(
            fundamentalHz = expectedHz,
            sampleRate = sampleRate,
            size = frameSize,
            harmonicAmplitudes = listOf(0.70, 0.44, 0.27, 0.14),
            phaseRadians = 0.41,
        )
        val interferer = SignalFixtures.sine(
            frequencyHz = 731.0,
            sampleRate = sampleRate,
            size = frameSize,
            amplitude = 0.22,
            phaseRadians = 1.1,
        )
        val noise = SignalFixtures.whiteNoise(frameSize, amplitude = 0.08, seed = 0xA11CE5L)
        val frame = SignalFixtures.mix(pitched, interferer, noise)

        val proposal = assertNotNull(detector.propose(frame, sampleRate))

        assertWithinCents(expectedHz, proposal.frequencyHz, toleranceCents = 10.0)
    }

    @Test
    fun `returns no proposal for silence`() {
        assertNull(detector.propose(FloatArray(frameSize), sampleRate))
    }

    @Test
    fun `every proposal respects the configured 40 to 1500 Hz search bounds`() {
        listOf(20.0, 40.0, 1500.0, 2000.0).forEach { toneHz ->
            val proposal = detector.propose(
                SignalFixtures.sine(toneHz, sampleRate, frameSize, amplitude = 0.8),
                sampleRate,
            )

            if (proposal != null) {
                assertTrue(
                    proposal.frequencyHz in 40.0..1500.0,
                    "$toneHz Hz input produced out-of-range ${proposal.frequencyHz} Hz",
                )
            }
        }
    }

    @Test
    fun `reuses detector state without mutating caller frames`() {
        val first = SignalFixtures.sine(110.0, sampleRate, frameSize, phaseRadians = 0.2)
        val second = SignalFixtures.sine(440.0, sampleRate, frameSize, phaseRadians = 0.9)
        val firstCopy = first.copyOf()
        val secondCopy = second.copyOf()

        val low = assertNotNull(detector.propose(first, sampleRate))
        val high = assertNotNull(detector.propose(second, sampleRate))

        assertWithinCents(110.0, low.frequencyHz, toleranceCents = 15.0)
        assertWithinCents(440.0, high.frequencyHz, toleranceCents = 10.0)
        assertTrue(first.contentEquals(firstCopy))
        assertTrue(second.contentEquals(secondCopy))
    }

    private fun assertWithinCents(expectedHz: Double, actualHz: Double, toleranceCents: Double) {
        val error = abs(centsError(expectedHz, actualHz))
        assertTrue(
            error <= toleranceCents,
            "expected $expectedHz Hz, got $actualHz Hz ($error cents; tolerance $toleranceCents)",
        )
    }

    private fun centsError(expectedHz: Double, actualHz: Double): Double =
        1200.0 * ln(actualHz / expectedHz) / ln(2.0)
}
