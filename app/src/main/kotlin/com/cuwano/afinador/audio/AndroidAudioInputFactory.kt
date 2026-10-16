package com.cuwano.afinador.audio

import android.media.AudioFormat
import android.media.MediaRecorder

/** One capture configuration to request, tried in the order the factory lists them. */
data class AudioCaptureAttempt(val audioSource: Int, val encoding: Int)

/** Builds the mono [AudioInput] for a single [attempt], or throws when it is unsupported. */
fun interface AudioRecorderCreator {
    fun create(requestedSampleRate: Int, attempt: AudioCaptureAttempt): AudioInput
}

/**
 * Resolves the first capture configuration the device can actually open.
 *
 * Each [attempts] entry is tried in order and the first usable [AudioInput] is returned;
 * later configurations are never built. A recorder that reports a non-positive sample rate
 * cannot carry audio, so it is released before the chain moves on. When every attempt fails,
 * a single error is surfaced: the last failure keeps its user-visible message and carries
 * the earlier rejections as suppressed, so nothing is lost but only one error propagates.
 */
class AndroidAudioInputFactory(
    private val creator: AudioRecorderCreator = AndroidAudioInputCreator(),
    private val attempts: List<AudioCaptureAttempt> = DEFAULT_ATTEMPTS,
) : AudioInputFactory {

    override fun create(requestedSampleRate: Int): AudioInput {
        require(requestedSampleRate > 0) { "requestedSampleRate must be positive" }
        val failures = ArrayList<Throwable>(attempts.size)
        for (attempt in attempts) {
            try {
                val input = creator.create(requestedSampleRate, attempt)
                if (input.sampleRate > 0) return input
                input.release()
                failures += IllegalStateException(
                    "Capture at $requestedSampleRate Hz reported an unusable sample rate.",
                )
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        val surfaced = failures.lastOrNull()
            ?: IllegalStateException("No audio capture configuration is available.")
        failures.dropLast(1).forEach(surfaced::addSuppressed)
        throw surfaced
    }

    companion object {
        /**
         * Unprocessed float capture is cleanest when the device grants it; the processed
         * microphone is the common fallback; 16-bit PCM covers devices without float support.
         */
        val DEFAULT_ATTEMPTS: List<AudioCaptureAttempt> = listOf(
            AudioCaptureAttempt(MediaRecorder.AudioSource.UNPROCESSED, AudioFormat.ENCODING_PCM_FLOAT),
            AudioCaptureAttempt(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_FLOAT),
            AudioCaptureAttempt(MediaRecorder.AudioSource.UNPROCESSED, AudioFormat.ENCODING_PCM_16BIT),
            AudioCaptureAttempt(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT),
            AudioCaptureAttempt(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
    }
}

/** Real [AudioRecorderCreator] backed by [AndroidAudioInput] and the platform recorder. */
class AndroidAudioInputCreator : AudioRecorderCreator {
    override fun create(requestedSampleRate: Int, attempt: AudioCaptureAttempt): AudioInput =
        AndroidAudioInput(
            requestedSampleRate = requestedSampleRate,
            audioSource = attempt.audioSource,
            encoding = attempt.encoding,
        )
}
