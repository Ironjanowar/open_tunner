package com.cuwano.afinador.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contracts for the input normaliser that lets quiet notes be detected without
 * playing loudly.
 *
 * Normalisation only ever amplifies: loud input is never attenuated, so a
 * well-leveled player is unaffected. The frame is scaled in place by
 * `clamp(targetRms / frameRms, 1.0, maximumGain)` and that same factor is
 * returned, so the caller can reason about how much gain was applied.
 */
class InputNormalizerTest {
    @Test
    fun `quiet frame is boosted toward the target rms`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 1000.0
        val samples = sine(440.0, SAMPLE_RATE, FRAME_SIZE, amplitude = 0.02)
        val beforeRms = rms(samples, samples.size)
        assertTrue(beforeRms < 0.12, "fixture must be quieter than the target")

        val gain = normalizer.normalize(samples, samples.size)

        assertTrue(gain > 1.0, "a quiet frame must be amplified, gain was $gain")
        assertEquals(0.12 / beforeRms, gain, 0.12 / beforeRms * 1e-3)
        assertEquals(0.12, rms(samples, samples.size), 1e-4)
    }

    @Test
    fun `loud frame is never attenuated and is left untouched`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        val samples = sine(440.0, SAMPLE_RATE, FRAME_SIZE, amplitude = 0.70)
        val original = samples.copyOf()
        assertTrue(rms(original, original.size) > 0.12, "fixture must be louder than the target")

        val gain = normalizer.normalize(samples, samples.size)

        assertEquals(1.0, gain, 0.0)
        assertTrue(samples.contentEquals(original), "a loud frame must be left byte-for-byte untouched")
    }

    @Test
    fun `applied gain never exceeds the configured maximum`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 2.0
        val samples = sine(440.0, SAMPLE_RATE, FRAME_SIZE, amplitude = 0.005)
        val beforeRms = rms(samples, samples.size)

        val gain = normalizer.normalize(samples, samples.size)

        assertEquals(2.0, gain, 1e-12, "gain must be clamped to maximumGain")
        assertEquals(beforeRms * 2.0, rms(samples, samples.size), 1e-6)
    }

    @Test
    fun `silent frame is untouched with unity gain`() {
        val normalizer = InputNormalizer()
        normalizer.maximumGain = 16.0
        val samples = FloatArray(FRAME_SIZE)

        val gain = normalizer.normalize(samples, samples.size)

        assertEquals(1.0, gain, 0.0)
        assertTrue(samples.all { it == 0f }, "a silent frame must be left untouched")
    }

    @Test
    fun `returned gain equals the per-sample scale factor actually applied`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        val samples = sine(220.0, SAMPLE_RATE, FRAME_SIZE, amplitude = 0.05)
        val original = samples.copyOf()
        val beforeRms = rms(original, original.size)

        val gain = normalizer.normalize(samples, samples.size)
        assertTrue(gain > 1.0)

        assertEquals(gain, rms(samples, samples.size) / beforeRms, 1e-6)
        original.indices.forEach { index ->
            assertEquals(
                original[index].toDouble() * gain,
                samples[index].toDouble(),
                1e-5,
                "sample $index must be scaled exactly by the returned gain",
            )
        }
    }

    @Test
    fun `count bounds which samples are normalized and is range checked`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        val samples = FloatArray(FRAME_SIZE)
        for (index in 0 until FRAME_SIZE / 2) {
            samples[index] = (0.01 * sin(2.0 * PI * 440.0 * index / SAMPLE_RATE)).toFloat()
        }
        for (index in FRAME_SIZE / 2 until FRAME_SIZE) {
            samples[index] = 0.5f
        }
        val untouchedTail = samples.copyOfRange(FRAME_SIZE / 2, FRAME_SIZE)

        val gain = normalizer.normalize(samples, count = FRAME_SIZE / 2)

        assertTrue(gain > 1.0)
        assertTrue(
            samples.copyOfRange(FRAME_SIZE / 2, FRAME_SIZE).contentEquals(untouchedTail),
            "samples beyond count must be untouched",
        )

        assertFailsWith<IllegalArgumentException>("count greater than the buffer must be rejected") {
            normalizer.normalize(samples, count = FRAME_SIZE + 1)
        }
        assertFailsWith<IllegalArgumentException>("a negative count must be rejected") {
            normalizer.normalize(samples, count = -1)
        }
    }

    @Test
    fun `zero count leaves the buffer untouched with unity gain`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        val samples = sine(440.0, SAMPLE_RATE, FRAME_SIZE, amplitude = 0.02)
        val original = samples.copyOf()

        val gain = normalizer.normalize(samples, count = 0)

        assertEquals(1.0, gain, 0.0)
        assertTrue(samples.contentEquals(original), "an empty request must not modify the buffer")
    }

    @Test
    fun `maximum gain must remain above unity`() {
        val normalizer = InputNormalizer()
        normalizer.maximumGain = 4.0
        assertEquals(4.0, normalizer.maximumGain, 0.0)
        assertTrue(normalizer.maximumGain > 1.0, "a freshly configured gain cap must exceed one")

        listOf(1.0, 0.5, 0.0, -3.0).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>("maximumGain $invalid must be rejected") {
                normalizer.maximumGain = invalid
            }
        }
    }

    @Test
    fun `normalised samples are clamped into full scale`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        // A quiet frame with a high crest factor: the gain that lifts it to the
        // target would push these two samples far past full scale.
        val samples = FloatArray(FRAME_SIZE)
        samples[10] = 0.9f
        samples[2000] = -0.8f

        normalizer.normalize(samples, samples.size)

        assertTrue(
            samples.all { it >= -1.0f && it <= 1.0f },
            "normalisation must keep every sample inside [-1, 1] " +
                "(min=${samples.min()} max=${samples.max()})",
        )
    }

    @Test
    fun `the gain is measured on the dc free frame`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        val ac = sine(440.0, SAMPLE_RATE, FRAME_SIZE, amplitude = 0.02)
        val withOffset = FloatArray(FRAME_SIZE) { ac[it] + 0.5f }
        val acCopy = ac.copyOf()

        val acGain = normalizer.normalize(ac, ac.size)
        val offsetGain = normalizer.normalize(withOffset, withOffset.size)

        assertTrue(acGain > 1.0, "a quiet AC frame must be boosted, gain was $acGain")
        // A relative tolerance: the mid-range bias estimate was exactly zero for a
        // symmetric frame and exactly the pedestal for a DC-pedestalled one, which
        // forced that unnatural estimator. The frame mean is not exactly zero over a
        // non-integer number of periods, so the two gains now agree only to a relative
        // 1e-6 rather than to the last bit.
        assertEquals(
            acGain,
            offsetGain,
            acGain * 1e-6,
            "a large DC offset must not cost the frame its boost: gain is $offsetGain instead of $acGain",
        )
        assertEquals(0.12, rms(acCopy, acCopy.size) * acGain, 0.12 * 1e-3)
    }

    @Test
    fun `the gain is not fooled by one large spike on a quiet frame`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        // A quiet tone plus one large spike: the frame is strongly asymmetric, so a
        // bias estimate taken from the mid-range (rather than the mean) reads a huge
        // "level" and silently drops the boost to unity.
        val samples = sine(440.0, SAMPLE_RATE, FRAME_SIZE, amplitude = 0.02)
        samples[10] = 0.9f
        val expected = acRmsGain(samples, targetRms = 0.12, maximumGain = 16.0)

        val gain = normalizer.normalize(samples, samples.size)

        assertTrue(
            gain in (expected / ASYMMETRY_FACTOR)..(expected * ASYMMETRY_FACTOR),
            "a lone spike must not fool the bias estimate: gain was $gain, the true AC-RMS gain is " +
                "about $expected (must stay within ${ASYMMETRY_FACTOR}x)",
        )
    }

    @Test
    fun `the gain is not fooled by a note attack transient`() {
        val normalizer = InputNormalizer(targetRms = 0.12)
        normalizer.maximumGain = 16.0
        // A quiet sustain tone carrying a loud, decaying attack transient: the onset
        // peak dwarfs the steady part, so the frame is asymmetric and a mid-range bias
        // estimate would again read a level far above the frame's true AC level.
        val samples = FloatArray(FRAME_SIZE) { index ->
            val sustain = 0.02 * sin(2.0 * PI * 440.0 * index / SAMPLE_RATE)
            val attack = 0.6 * exp(-index / 25.0) * cos(2.0 * PI * 440.0 * index / SAMPLE_RATE)
            (sustain + attack).toFloat()
        }
        val expected = acRmsGain(samples, targetRms = 0.12, maximumGain = 16.0)

        val gain = normalizer.normalize(samples, samples.size)

        assertTrue(
            gain in (expected / ASYMMETRY_FACTOR)..(expected * ASYMMETRY_FACTOR),
            "a note attack must not fool the bias estimate: gain was $gain, the true AC-RMS gain is " +
                "about $expected (must stay within ${ASYMMETRY_FACTOR}x)",
        )
    }

    /**
     * Gain implied by the true AC (mean-removed) RMS of the frame: the boost a
     * constant-bias estimate taken from the frame mean would apply.
     */
    private fun acRmsGain(samples: FloatArray, targetRms: Double, maximumGain: Double): Double {
        var mean = 0.0
        for (index in 0 until samples.size) mean += samples[index].toDouble()
        mean /= samples.size
        var squaredSum = 0.0
        for (index in 0 until samples.size) {
            val centered = samples[index].toDouble() - mean
            squaredSum += centered * centered
        }
        val acRms = sqrt(squaredSum / samples.size)
        if (acRms <= 0.0) return 1.0
        return (targetRms / acRms).coerceIn(1.0, maximumGain)
    }

    private fun sine(frequencyHz: Double, sampleRate: Int, size: Int, amplitude: Double): FloatArray =
        FloatArray(size) { index -> (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat() }

    private fun rms(samples: FloatArray, count: Int): Double {
        if (count == 0) return 0.0
        var sum = 0.0
        for (index in 0 until count) sum += samples[index].toDouble() * samples[index]
        return sqrt(sum / count)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SIZE = 4096

        /**
         * How far the applied gain may sit from the true AC-RMS gain on an asymmetric
         * or transient frame. A mean-based bias estimate lands within 1x; the old
         * mid-range estimate missed by 3.8x-6x, so 2x separates the two.
         */
        const val ASYMMETRY_FACTOR = 2.0
    }
}
