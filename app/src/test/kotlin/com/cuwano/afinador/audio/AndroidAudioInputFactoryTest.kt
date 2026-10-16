package com.cuwano.afinador.audio

import android.media.AudioFormat
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RED contract for the host-testable multi-source / multi-encoding fallback factory.
 *
 * Required production surface (package `com.cuwano.afinador.audio`):
 *
 * ```
 * data class AudioCaptureAttempt(val audioSource: Int, val encoding: Int)
 *
 * fun interface AudioRecorderCreator {
 *     fun create(requestedSampleRate: Int, attempt: AudioCaptureAttempt): AudioInput
 * }
 *
 * class AndroidAudioInputFactory(
 *     private val creator: AudioRecorderCreator = AndroidAudioInputCreator(),
 *     private val attempts: List<AudioCaptureAttempt> = DEFAULT_ATTEMPTS,
 * ) : AudioInputFactory
 * ```
 *
 * The recorder construction is injected through [AudioRecorderCreator] so the fallback
 * chain is exercised on the JVM. Default chain order:
 *
 * 1. `UNPROCESSED` / `ENCODING_PCM_FLOAT`
 * 2. `MIC` / `ENCODING_PCM_FLOAT`
 * 3. remaining variants request `ENCODING_PCM_16BIT`
 *
 * Guarantees under test: the first attempt the [AudioRecorderCreator] can build wins and
 * later attempts are not tried; an unusable recorder is released before the chain
 * continues; each rejected attempt is marked, and a single error is surfaced only once
 * every attempt has failed.
 */
class AndroidAudioInputFactoryTest {

    @Test
    fun `the default chain tries unprocessed float then mic float then pcm16 variants`() {
        val creator = RecordingCreator { throw IllegalStateException("rejected") }
        val factory = AndroidAudioInputFactory(creator = creator)

        try {
            factory.create(48_000)
            fail("every attempt rejects capture, so create must throw")
        } catch (_: Throwable) {
            // The chain rejected capture: inspect which attempts were tried.
        }

        val attempts = creator.attempts
        assertTrue("the chain must try more than the two float variants", attempts.size >= 3)
        assertEquals(
            AudioCaptureAttempt(MediaRecorder.AudioSource.UNPROCESSED, AudioFormat.ENCODING_PCM_FLOAT),
            attempts[0],
        )
        assertEquals(
            AudioCaptureAttempt(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_FLOAT),
            attempts[1],
        )
        assertTrue(
            "every attempt after the float variants must request 16-bit PCM",
            attempts.drop(2).all { it.encoding == AudioFormat.ENCODING_PCM_16BIT },
        )
    }

    @Test
    fun `the first supported attempt wins and later attempts are not tried`() {
        val winner = RecordingAudioInput(sampleRate = 48_000)
        val creator = RecordingCreator { winner }
        val factory = AndroidAudioInputFactory(creator = creator)

        assertSame(winner, factory.create(48_000))
        assertEquals("only the first attempt should be built", 1, creator.attempts.size)
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, creator.attempts.single().audioSource)
        assertEquals("the caller owns the returned recorder", 0, winner.releaseCalls.get())
    }

    @Test
    fun `falls back to the microphone float source when unprocessed float is rejected`() {
        val winner = RecordingAudioInput(sampleRate = 48_000)
        val creator = RecordingCreator { attempt ->
            when (attempt) {
                AudioCaptureAttempt(MediaRecorder.AudioSource.UNPROCESSED, AudioFormat.ENCODING_PCM_FLOAT) ->
                    throw IllegalStateException("no unprocessed float capture")

                AudioCaptureAttempt(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_FLOAT) -> winner

                else -> error("the chain must stop at the first supported attempt")
            }
        }
        val factory = AndroidAudioInputFactory(creator = creator)

        assertSame(winner, factory.create(48_000))
        assertEquals(
            listOf(
                AudioCaptureAttempt(MediaRecorder.AudioSource.UNPROCESSED, AudioFormat.ENCODING_PCM_FLOAT),
                AudioCaptureAttempt(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_FLOAT),
            ),
            creator.attempts,
        )
        assertEquals("the returned recorder is owned by the caller", 0, winner.releaseCalls.get())
    }

    @Test
    fun `falls back to a pcm16 variant when every float configuration is rejected`() {
        val winner = RecordingAudioInput(sampleRate = 48_000)
        val creator = RecordingCreator { attempt ->
            if (attempt.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                throw IllegalStateException("float capture is unavailable")
            }
            winner
        }
        val factory = AndroidAudioInputFactory(creator = creator)

        assertSame(winner, factory.create(48_000))
        assertTrue(
            "both float variants must be tried before pcm16",
            creator.attempts.take(2).all { it.encoding == AudioFormat.ENCODING_PCM_FLOAT },
        )
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, creator.attempts.last().encoding)
        assertEquals(0, winner.releaseCalls.get())
    }

    @Test
    fun `an unusable recorder is released and the chain continues`() {
        val unusable = RecordingAudioInput(sampleRate = 0)
        val winner = RecordingAudioInput(sampleRate = 48_000)
        val creator = RecordingCreator { attempt ->
            if (attempt.audioSource == MediaRecorder.AudioSource.UNPROCESSED &&
                attempt.encoding == AudioFormat.ENCODING_PCM_FLOAT
            ) {
                unusable
            } else {
                winner
            }
        }
        val factory = AndroidAudioInputFactory(creator = creator)

        assertSame(winner, factory.create(48_000))
        assertEquals("a recorder with no usable sample rate must be released", 1, unusable.releaseCalls.get())
        assertEquals("the returned recorder must not be released", 0, winner.releaseCalls.get())
    }

    @Test
    fun `every rejected attempt is marked and a single error is surfaced only after all fail`() {
        val ordinal = AtomicInteger(0)
        val creator = RecordingCreator { throw IllegalStateException("rejected ${ordinal.incrementAndGet()}") }
        val factory = AndroidAudioInputFactory(creator = creator)

        val surfaced = try {
            factory.create(48_000)
            null
        } catch (failure: Throwable) {
            failure
        }

        assertNotNull("create must throw when every attempt fails", surfaced)
        val failure = surfaced!!
        val tried = creator.attempts.size
        assertTrue("the whole chain must be tried before giving up", tried >= 3)
        assertTrue("the surfaced error needs a user-visible message", failure.message?.isNotBlank() == true)

        val marks = (listOfNotNull(failure.message) + failure.suppressed.mapNotNull { it.message }).toSet()
        (1..tried).forEach { ordinal ->
            assertTrue(
                "rejected attempt $ordinal must be marked on the single surfaced error",
                "rejected $ordinal" in marks,
            )
        }
        assertTrue(
            "the surfaced error must attach the earlier failures as suppressed",
            failure.suppressed.isNotEmpty(),
        )
    }

    @Test
    fun `the requested sample rate is forwarded to every attempt`() {
        val creator = RecordingCreator { throw IllegalStateException("rejected") }
        val factory = AndroidAudioInputFactory(creator = creator)

        try {
            factory.create(44_100)
        } catch (_: Throwable) {
            // Expected: the chain gave up. The recorded rates are what matters here.
        }

        assertTrue("at least one attempt must be tried", creator.requestedRates.isNotEmpty())
        assertTrue(
            "every attempt must receive the requested sample rate",
            creator.requestedRates.all { it == 44_100 },
        )
    }

    private class RecordingCreator(
        private val decide: (AudioCaptureAttempt) -> AudioInput,
    ) : AudioRecorderCreator {
        val attempts = mutableListOf<AudioCaptureAttempt>()
        val requestedRates = mutableListOf<Int>()

        override fun create(requestedSampleRate: Int, attempt: AudioCaptureAttempt): AudioInput {
            synchronized(this) {
                requestedRates += requestedSampleRate
                attempts += attempt
            }
            return decide(attempt)
        }
    }

    private class RecordingAudioInput(override val sampleRate: Int) : AudioInput {
        val releaseCalls = AtomicInteger()

        override fun start() = Unit

        override fun read(buffer: FloatArray): Int = 0

        override fun stop() = Unit

        override fun release() {
            releaseCalls.incrementAndGet()
        }
    }
}
