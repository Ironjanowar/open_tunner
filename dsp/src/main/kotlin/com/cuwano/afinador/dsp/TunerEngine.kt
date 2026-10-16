package com.cuwano.afinador.dsp

/** Integrates capture framing, signal quality, pitch estimation, and tracking. */
class TunerEngine(
    private val sampleRate: Int,
    private val frameSize: Int = 4096,
    private val hopSize: Int = 1024,
    minFrequencyHz: Double = 40.0,
    maxFrequencyHz: Double = 1500.0,
) {
    private val frames = SampleRingBuffer(frameSize, hopSize)
    private val dcRemoved = FloatArray(frameSize)
    private val salienceDetector = HarmonicSalienceDetector(frameSize, minFrequencyHz, maxFrequencyHz)
    private val yinDetector = YinDetector(frameSize, minFrequencyHz, maxFrequencyHz, YIN_CMND_THRESHOLD)
    private val noiseFloor = AdaptiveNoiseFloor(
        initialNoiseRms = INITIAL_NOISE_RMS,
        minimumNoiseRms = MINIMUM_NOISE_RMS,
        halfLifeSeconds = NOISE_HALF_LIFE_SECONDS,
        minimumSignalToNoiseRatio = MINIMUM_SIGNAL_TO_NOISE_RATIO,
    )
    private val tracker = PitchTracker(
        minimumPrimarySalience = MINIMUM_PRIMARY_SALIENCE,
        minimumYinConfidenceForOctaveCorrection = MINIMUM_YIN_CONFIDENCE,
        minimumSignalToNoiseRatio = MINIMUM_SIGNAL_TO_NOISE_RATIO,
        smoothingHalfLifeSeconds = SMOOTHING_HALF_LIFE_SECONDS,
        changeConfirmationSeconds = CHANGE_CONFIRMATION_SECONDS,
        clearAfterSeconds = CLEAR_AFTER_SECONDS,
        tunedEnterCents = TUNED_ENTER_CENTS,
        tunedExitCents = TUNED_EXIT_CENTS,
    )
    private val elapsedSeconds = hopSize.toDouble() / sampleRate

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(frameSize > 1) { "frameSize must be greater than one" }
        require(hopSize > 0) { "hopSize must be positive" }
        require(maxFrequencyHz <= sampleRate / 2.0) { "search range exceeds Nyquist frequency" }
    }

    fun accept(
        samples: FloatArray,
        count: Int = samples.size,
        onReading: (TunerReading?) -> Unit,
    ) {
        frames.accept(samples, count) { frame ->
            onReading(analyze(frame))
        }
    }

    fun reset() {
        frames.reset()
        noiseFloor.reset()
        tracker.reset()
        dcRemoved.fill(0f)
    }

    private fun analyze(frame: FloatArray): TunerReading? {
        val measurement = SignalMetrics.analyze(frame, dcRemoved)
        val proposal = salienceDetector.propose(dcRemoved, sampleRate)
        val yin = yinDetector.analyze(dcRemoved, sampleRate)
        val normalizedSalience = normalizeSalience(proposal?.salience ?: 0.0, measurement.rms)
        val reliablePitch = !measurement.clipped &&
            normalizedSalience >= MINIMUM_PRIMARY_SALIENCE &&
            (yin?.confidence ?: 0.0) >= MINIMUM_RELIABLE_YIN_CONFIDENCE

        // The tracker and gate use the floor from before this observation. Only
        // afterward may an unpitched, unclipped frame teach the ambient level.
        val tracked = tracker.update(
            PitchTrackerFrame(
                primaryFrequencyHz = proposal?.frequencyHz,
                primarySalience = normalizedSalience,
                yinFrequencyHz = yin?.frequencyHz,
                yinConfidence = yin?.confidence ?: 0.0,
                rms = measurement.rms,
                noiseRms = noiseFloor.noiseRms,
                isClipped = measurement.clipped,
            ),
            elapsedSeconds,
        )
        noiseFloor.observe(
            rms = measurement.rms,
            elapsedSeconds = elapsedSeconds,
            hasReliablePitch = reliablePitch,
            isClipped = measurement.clipped,
        )
        if (measurement.clipped) return null
        val pitch = tracked ?: return null
        val note = NoteMapper.map(pitch.frequencyHz) ?: return null
        return TunerReading(
            noteName = note.noteName,
            octave = note.octave,
            frequencyHz = pitch.frequencyHz,
            cents = pitch.cents,
            confidence = pitch.confidence.coerceIn(0.0, 1.0),
            isTuned = pitch.isTuned,
            rms = measurement.rms,
            clipped = measurement.clipped,
        )
    }

    /**
     * Raw harmonic salience is an unscaled FFT magnitude sum. Dividing by
     * frame RMS and FFT size makes it amplitude- and frame-size-independent;
     * the reference ratio maps a Hann-windowed sinusoid close to full scale.
     */
    private fun normalizeSalience(rawSalience: Double, rms: Double): Double {
        if (!rawSalience.isFinite() || rawSalience <= 0.0 || rms <= 0.0) return 0.0
        return (rawSalience / (frameSize * rms * SALIENCE_REFERENCE_RATIO)).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val YIN_CMND_THRESHOLD = 0.15
        const val MINIMUM_PRIMARY_SALIENCE = 0.35
        const val MINIMUM_RELIABLE_YIN_CONFIDENCE = 0.70
        const val MINIMUM_YIN_CONFIDENCE = 0.80
        const val MINIMUM_SIGNAL_TO_NOISE_RATIO = 3.0
        const val INITIAL_NOISE_RMS = 0.010
        const val MINIMUM_NOISE_RMS = 0.002
        const val NOISE_HALF_LIFE_SECONDS = 5.0
        const val SALIENCE_REFERENCE_RATIO = 0.25
        const val SMOOTHING_HALF_LIFE_SECONDS = 0.025
        const val CHANGE_CONFIRMATION_SECONDS = 0.080
        const val CLEAR_AFTER_SECONDS = 0.200
        const val TUNED_ENTER_CENTS = 5.0
        const val TUNED_EXIT_CENTS = 8.0
    }
}
