package com.cuwano.afinador.tuner

import com.cuwano.afinador.audio.AudioInput
import com.cuwano.afinador.audio.AudioInputFactory
import com.cuwano.afinador.dsp.SensitivityProfile
import com.cuwano.afinador.dsp.TunerReading

sealed interface TunerSessionState {
    data object Stopped : TunerSessionState
    data object Listening : TunerSessionState
    data class Error(val message: String) : TunerSessionState
}

/** Owns one audio input, one processor, and one capture worker at a time. */
class TunerSession(
    private val audioInputFactory: AudioInputFactory,
    private val processorFactory: (sampleRate: Int) -> TunerProcessor,
    private val captureBufferSize: Int = DEFAULT_CAPTURE_BUFFER_SIZE,
) {
    private val lifecycleLock = Any()

    @Volatile
    var state: TunerSessionState = TunerSessionState.Stopped
        private set

    @Volatile
    var latestReading: TunerReading? = null
        private set

    /**
     * Whether the CURRENT capture run has published at least one live reading.
     * Cleared in [start], set when a published reading with [TunerReading.isLive]
     * true arrives. The coordinator reads it so a note held on screen across a
     * stop/start is not presented as live (or tuned) until the rebuilt run itself
     * hears something; [latestReading] keeps the previous note even so, so the
     * survivor contract of `a displayed note survives stop and start` holds.
     */
    @Volatile
    var runHasLiveReading: Boolean = false
        private set

    /** Detector sensitivity applied to the current and every future processor. */
    @Volatile
    var sensitivity: SensitivityProfile = SensitivityProfile.DEFAULT
        private set

    @Volatile
    private var running = false

    private var input: AudioInput? = null
    private var processor: TunerProcessor? = null
    private var worker: Thread? = null

    @Volatile
    private var generation = 0L

    init {
        require(captureBufferSize > 0) { "captureBufferSize must be positive" }
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return

            // A new run has not heard anything yet: the note still on screen from
            // the previous run must not be shown as live or tuned until this run
            // publishes its own live reading.
            runHasLiveReading = false

            var startedInput: AudioInput? = null
            var lastFailure: Throwable? = null

            for (rate in PREFERRED_SAMPLE_RATES) {
                val candidate = try {
                    audioInputFactory.create(rate)
                } catch (failure: Throwable) {
                    lastFailure = failure
                    continue
                }
                try {
                    candidate.start()
                    startedInput = candidate
                    break
                } catch (failure: Throwable) {
                    lastFailure = failure
                    candidate.releaseSafely()
                }
            }

            if (startedInput == null) {
                state = TunerSessionState.Error(
                    lastFailure?.message?.takeIf { it.isNotBlank() }
                        ?: "Unable to start microphone capture.",
                )
                return
            }

            val startedProcessor = try {
                processorFactory(startedInput.sampleRate)
            } catch (failure: Throwable) {
                startedInput.stopSafely()
                startedInput.releaseSafely()
                state = TunerSessionState.Error(
                    failure.message?.takeIf { it.isNotBlank() }
                        ?: "Unable to start audio processing.",
                )
                return
            }

            generation += 1
            val workerGeneration = generation
            input = startedInput
            processor = startedProcessor
            startedProcessor.setProfile(sensitivity)
            running = true
            state = TunerSessionState.Listening
            worker = Thread(
                { captureLoop(startedInput, startedProcessor, workerGeneration) },
                "TunerSession-capture",
            ).also { it.start() }
        }
    }

    fun stop() {
        val resources = synchronized(lifecycleLock) {
            val activeInput = input
            val activeProcessor = processor
            val activeWorker = worker
            if (activeInput == null && activeProcessor == null && activeWorker == null) {
                running = false
                state = TunerSessionState.Stopped
                return
            }

            running = false
            generation += 1
            input = null
            processor = null
            worker = null
            state = TunerSessionState.Stopped
            Resources(activeInput, activeProcessor, activeWorker)
        }

        resources.input?.stopSafely()
        val activeWorker = resources.worker
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            try {
                activeWorker.join()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        resources.input?.releaseSafely()
        resources.processor?.reset()
    }

    /**
     * Applies a new sensitivity without restarting capture: the running
     * processor is updated in place and any processor created later inherits it.
     */
    fun setSensitivity(profile: SensitivityProfile) {
        sensitivity = profile
        synchronized(lifecycleLock) { processor }?.setProfile(profile)
    }

    private fun captureLoop(
        ownedInput: AudioInput,
        ownedProcessor: TunerProcessor,
        workerGeneration: Long,
    ) {
        val buffer = FloatArray(captureBufferSize)
        while (isCurrent(workerGeneration)) {
            val count = try {
                ownedInput.read(buffer)
            } catch (failure: Throwable) {
                failWorker(ownedInput, ownedProcessor, workerGeneration, failure.message)
                return
            }

            if (!isCurrent(workerGeneration)) return
            if (count < 0) {
                failWorker(
                    ownedInput,
                    ownedProcessor,
                    workerGeneration,
                    "Microphone read failed (error $count).",
                )
                return
            }
            if (count == 0) continue
            if (count > buffer.size) {
                failWorker(
                    ownedInput,
                    ownedProcessor,
                    workerGeneration,
                    "Microphone returned an invalid sample count.",
                )
                return
            }

            try {
                ownedProcessor.accept(buffer, count) { reading ->
                    if (isCurrent(workerGeneration)) publish(reading)
                }
            } catch (failure: Throwable) {
                failWorker(ownedInput, ownedProcessor, workerGeneration, failure.message)
                return
            }
        }
    }

    /**
     * A null reading (a freshly rebuilt engine's first frames, or a frame with no
     * pitch) must not wipe a note already on screen: it is re-published as a
     * stale copy of the note currently displayed. Only a genuinely live reading
     * can bring the displayed note back live, and a live reading also marks the
     * current run as having heard something.
     */
    private fun publish(reading: TunerReading?) {
        if (reading != null && reading.isLive) runHasLiveReading = true
        latestReading = reading ?: latestReading?.copy(isLive = false)
    }

    private fun isCurrent(workerGeneration: Long): Boolean =
        running && generation == workerGeneration

    private fun failWorker(
        ownedInput: AudioInput,
        ownedProcessor: TunerProcessor,
        workerGeneration: Long,
        detail: String?,
    ) {
        val ownsResources = synchronized(lifecycleLock) {
            if (!running || generation != workerGeneration || input !== ownedInput) {
                false
            } else {
                running = false
                generation += 1
                input = null
                processor = null
                worker = null
                latestReading = null
                state = TunerSessionState.Error(
                    detail?.takeIf { it.isNotBlank() } ?: "Audio capture failed.",
                )
                true
            }
        }
        if (ownsResources) {
            ownedInput.stopSafely()
            ownedInput.releaseSafely()
            ownedProcessor.reset()
        }
    }

    private data class Resources(
        val input: AudioInput?,
        val processor: TunerProcessor?,
        val worker: Thread?,
    )

    private companion object {
        val PREFERRED_SAMPLE_RATES = intArrayOf(48_000, 44_100)
        const val DEFAULT_CAPTURE_BUFFER_SIZE = 2048
    }
}

private fun AudioInput.stopSafely() {
    try {
        stop()
    } catch (_: Throwable) {
        // Cleanup continues through release.
    }
}

private fun AudioInput.releaseSafely() {
    try {
        release()
    } catch (_: Throwable) {
        // Cleanup is best effort; lifecycle ownership has already been relinquished.
    }
}
