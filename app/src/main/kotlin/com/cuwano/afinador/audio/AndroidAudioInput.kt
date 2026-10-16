package com.cuwano.afinador.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/**
 * Blocking mono microphone capture. RECORD_AUDIO permission must be granted by the caller.
 * Samples always reach the caller as normalized floats: `ENCODING_PCM_FLOAT` recorders hand
 * them over directly, while `ENCODING_PCM_16BIT` recorders are read as shorts and converted
 * through [Pcm16] into the same `FloatArray` contract.
 *
 * The runtime grant is enforced and re-checked by the activity before capture starts, and any
 * construction failure propagates through TunerSession as a user-visible error, so the
 * unverifiable permission check on this platform wrapper is suppressed.
 */
@SuppressLint("MissingPermission")
class AndroidAudioInput(
    requestedSampleRate: Int,
    audioSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    private val encoding: Int = AudioFormat.ENCODING_PCM_FLOAT,
) : AudioInput {
    private val record: AudioRecord
    private val lifecycleLock = Any()
    private var started = false
    private var released = false

    /** Reused only on the 16-bit path, sized lazily to the caller's buffer. */
    private var pcm16Buffer = ShortArray(0)

    override val sampleRate: Int

    init {
        require(requestedSampleRate > 0) { "requestedSampleRate must be positive" }
        val minimumBytes = AudioRecord.getMinBufferSize(
            requestedSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            encoding,
        )
        require(minimumBytes > 0) {
            "Capture encoding $encoding is unavailable at $requestedSampleRate Hz (error $minimumBytes)"
        }

        record = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(requestedSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minimumBytes, MINIMUM_CAPTURE_SAMPLES * bytesPerSample()))
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize at $requestedSampleRate Hz")
        }
        sampleRate = record.sampleRate
    }

    override fun start() {
        synchronized(lifecycleLock) {
            check(!released) { "Audio input has been released" }
            if (started) return
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter recording state")
            }
            started = true
        }
    }

    override fun read(buffer: FloatArray): Int {
        require(buffer.isNotEmpty()) { "buffer must not be empty" }
        val shorts = synchronized(lifecycleLock) {
            check(started && !released) { "Audio input is not started" }
            if (encoding == AudioFormat.ENCODING_PCM_16BIT) pcm16BufferFor(buffer.size) else null
        }
        if (shorts == null) {
            return record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        }
        val count = record.read(shorts, 0, buffer.size, AudioRecord.READ_BLOCKING)
        if (count > 0) Pcm16.toFloats(shorts, count, buffer, 0)
        return count
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            if (!started || released) return
            started = false
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // The recorder may already have left the recording state after a platform error.
            }
        }
    }

    override fun release() {
        synchronized(lifecycleLock) {
            if (released) return
            if (started) {
                started = false
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                    // Release remains required even when stopping a failed recorder is rejected.
                }
            }
            released = true
            record.release()
        }
    }

    private fun pcm16BufferFor(size: Int): ShortArray {
        if (pcm16Buffer.size < size) pcm16Buffer = ShortArray(size)
        return pcm16Buffer
    }

    private fun bytesPerSample(): Int =
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) Short.SIZE_BYTES else Float.SIZE_BYTES

    private companion object {
        const val MINIMUM_CAPTURE_SAMPLES = 4096
    }
}
