package com.cuwano.afinador.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YinDetectorTest {
    private val sampleRate = 48_000
    private val frameSize = 8192
    private val detector = YinDetector(
        frameSize = frameSize,
        minFrequencyHz = 40.0,
        maxFrequencyHz = 1500.0,
        cmndThreshold = 0.15,
    )

    @Test
    fun `configured frequency endpoints remain detectable at 44100 Hz`() {
        assertConfiguredEndpointsAreDetected(sampleRate = 44_100)
    }

    @Test
    fun `configured frequency endpoints remain detectable at 48000 Hz`() {
        assertConfiguredEndpointsAreDetected(sampleRate = 48_000)
    }

    @Test
    fun `pure tones outside configured range are rejected at 44100 Hz`() {
        assertOutOfRangePureTonesAreRejected(sampleRate = 44_100)
    }

    @Test
    fun `pure tones outside configured range are rejected at 48000 Hz`() {
        assertOutOfRangePureTonesAreRejected(sampleRate = 48_000)
    }

    @Test
    fun `clean sines produce accurate frequency and strong confidence across the tuner range`() {
        listOf(55.0, 110.0, 440.0, 1000.0).forEach { expectedHz ->
            val result = assertNotNull(
                detector.analyze(
                    SignalFixtures.sine(
                        frequencyHz = expectedHz,
                        sampleRate = sampleRate,
                        size = frameSize,
                        amplitude = 0.72,
                        phaseRadians = 0.37,
                    ),
                    sampleRate,
                ),
                "$expectedHz Hz",
            )

            assertWithinCents(expectedHz, result.frequencyHz, toleranceCents = 5.0)
            assertTrue(result.confidence >= 0.90, "$expectedHz Hz confidence was ${result.confidence}")
        }
    }

    @Test
    fun `harmonic signals retain their fundamental and a usable confidence`() {
        listOf(55.0, 110.0, 440.0, 1000.0).forEach { expectedHz ->
            val result = assertNotNull(
                detector.analyze(
                    SignalFixtures.harmonicMixture(
                        fundamentalHz = expectedHz,
                        sampleRate = sampleRate,
                        size = frameSize,
                        harmonicAmplitudes = listOf(0.55, 0.82, 0.38, 0.21, 0.12),
                        phaseRadians = 0.29,
                    ),
                    sampleRate,
                ),
                "$expectedHz Hz",
            )

            assertWithinCents(expectedHz, result.frequencyHz, toleranceCents = 8.0)
            assertTrue(result.confidence >= 0.70, "$expectedHz Hz confidence was ${result.confidence}")
        }
    }

    @Test
    fun `silence has no YIN observation`() {
        assertNull(detector.analyze(FloatArray(frameSize), sampleRate))
    }

    @Test
    fun `seeded broadband noise does not hide a dominant periodic signal`() {
        val expectedHz = 440.0
        val frame = SignalFixtures.mix(
            SignalFixtures.sine(expectedHz, sampleRate, frameSize, amplitude = 0.65, phaseRadians = 0.83),
            SignalFixtures.whiteNoise(frameSize, amplitude = 0.12, seed = 0x51A1CE),
        )

        val result = assertNotNull(detector.analyze(frame, sampleRate))

        assertWithinCents(expectedHz, result.frequencyHz, toleranceCents = 10.0)
        assertTrue(result.confidence >= 0.55, "noisy confidence was ${result.confidence}")
    }

    @Test
    fun `attack transient is accurate and usable or abstains rather than reporting an octave`() {
        val expectedHz = 220.0
        val attackSamples = frameSize / 5
        val frame = FloatArray(frameSize) { index ->
            if (index < attackSamples) {
                0.0f
            } else {
                val attackGain = ((index - attackSamples).toDouble() / attackSamples).coerceIn(0.0, 1.0)
                (0.75 * attackGain * sin(2.0 * PI * expectedHz * index / sampleRate + 0.41)).toFloat()
            }
        }

        assertAccurateUsableOrAbsent(
            expectedHz = expectedHz,
            observation = detector.analyze(frame, sampleRate),
            toleranceCents = 15.0,
            minimumConfidence = 0.70,
        )
    }

    @Test
    fun `mildly inharmonic partials are accurate and usable or abstain rather than reporting an octave`() {
        val expectedHz = 110.0
        val inharmonicity = 0.0004
        val amplitudes = listOf(0.20, 0.72, 0.43, 0.26, 0.14)
        val frame = FloatArray(frameSize) { index ->
            amplitudes.indices.sumOf { partialIndex ->
                val partialNumber = partialIndex + 1
                val stretchedHz = expectedHz * partialNumber *
                    kotlin.math.sqrt(1.0 + inharmonicity * partialNumber * partialNumber)
                amplitudes[partialIndex] * sin(
                    2.0 * PI * stretchedHz * index / sampleRate + 0.23 * partialNumber,
                )
            }.toFloat()
        }

        assertAccurateUsableOrAbsent(
            expectedHz = expectedHz,
            observation = detector.analyze(frame, sampleRate),
            toleranceCents = 15.0,
            minimumConfidence = 0.70,
        )
    }

    @Test
    fun `confidence follows the standard CMND threshold dip`() {
        val result = assertNotNull(
            detector.analyze(
                SignalFixtures.sine(110.0, sampleRate, frameSize, amplitude = 0.8, phaseRadians = 1.17),
                sampleRate,
            ),
        )

        assertTrue(result.cmnd <= 0.15, "selected CMND ${result.cmnd} did not cross the configured threshold")
        assertEquals(1.0 - result.cmnd, result.confidence, absoluteTolerance = 1e-12)
        assertTrue(result.confidence in 0.0..1.0)
    }

    @Test
    fun `YIN observation exposes octave disagreement with the primary salience proposal`() {
        val yin = assertNotNull(
            detector.analyze(
                SignalFixtures.sine(440.0, sampleRate, frameSize, amplitude = 0.8),
                sampleRate,
            ),
        )

        assertTrue(yin.hasOctaveDisagreement(primaryFrequencyHz = 880.0))
        assertTrue(yin.hasOctaveDisagreement(primaryFrequencyHz = 220.0))
        assertFalse(yin.hasOctaveDisagreement(primaryFrequencyHz = 443.0))
    }

    private fun assertWithinCents(expectedHz: Double, actualHz: Double, toleranceCents: Double) {
        val error = abs(1200.0 * ln(actualHz / expectedHz) / ln(2.0))
        assertTrue(
            error <= toleranceCents,
            "expected $expectedHz Hz, got $actualHz Hz ($error cents; tolerance $toleranceCents)",
        )
    }

    private fun assertAccurateUsableOrAbsent(
        expectedHz: Double,
        observation: YinObservation?,
        toleranceCents: Double,
        minimumConfidence: Double,
    ) {
        if (observation == null) return
        assertWithinCents(expectedHz, observation.frequencyHz, toleranceCents)
        assertTrue(
            observation.confidence >= minimumConfidence,
            "${observation.frequencyHz} Hz observation confidence was ${observation.confidence}; " +
                "minimum usable confidence is $minimumConfidence",
        )
    }

    private fun assertConfiguredEndpointsAreDetected(sampleRate: Int) {
        listOf(40.0, 1_500.0).forEach { expectedHz ->
            val boundaryDetector = YinDetector(
                frameSize = frameSize,
                minFrequencyHz = 40.0,
                maxFrequencyHz = 1_500.0,
                cmndThreshold = 0.15,
            )
            val result = assertNotNull(
                boundaryDetector.analyze(
                    SignalFixtures.sine(expectedHz, sampleRate, frameSize, amplitude = 0.72, phaseRadians = 0.37),
                    sampleRate,
                ),
                "$expectedHz Hz at $sampleRate Hz must not be suppressed by a rounded lag bound",
            )

            assertWithinCents(expectedHz, result.frequencyHz, toleranceCents = 8.0)
        }
    }

    private fun assertOutOfRangePureTonesAreRejected(sampleRate: Int) {
        val boundaryDetector = YinDetector(
            frameSize = frameSize,
            minFrequencyHz = 40.0,
            maxFrequencyHz = 1_500.0,
            cmndThreshold = 0.15,
        )
        listOf(39.0, 39.9, 1_500.1, 1_600.0).forEach { frequencyHz ->
            assertNull(
                boundaryDetector.analyze(
                    SignalFixtures.sine(
                        frequencyHz = frequencyHz,
                        sampleRate = sampleRate,
                        size = frameSize,
                        amplitude = 0.72,
                        phaseRadians = 0.37,
                    ),
                    sampleRate,
                ),
                "$frequencyHz Hz must be rejected outside the configured 40-1500 Hz range at $sampleRate Hz",
            )
        }
    }
}
