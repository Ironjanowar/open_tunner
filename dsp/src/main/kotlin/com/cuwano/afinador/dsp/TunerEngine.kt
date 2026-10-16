package com.cuwano.afinador.dsp

import kotlin.math.sqrt

/** Integrates capture framing, level normalization, signal quality, pitch estimation, and tracking. */
class TunerEngine(
    private val sampleRate: Int,
    private val frameSize: Int = 4096,
    private val hopSize: Int = 1024,
    private val minFrequencyHz: Double = 40.0,
    private val maxFrequencyHz: Double = 1500.0,
    profile: SensitivityProfile = SensitivityProfile.DEFAULT,
) {
    private val frames = SampleRingBuffer(frameSize, hopSize)
    private val dcRemoved = FloatArray(frameSize)
    private val normalizer = InputNormalizer()
    private val salienceDetector = HarmonicSalienceDetector(frameSize, minFrequencyHz, maxFrequencyHz)
    private val yinDetector = YinDetector(frameSize, minFrequencyHz, maxFrequencyHz, YIN_CMND_THRESHOLD)
    private val noiseFloor = AdaptiveNoiseFloor(
        initialNoiseRms = INITIAL_NOISE_RMS,
        minimumNoiseRms = profile.minimumNoiseRms,
        halfLifeSeconds = NOISE_HALF_LIFE_SECONDS,
        minimumSignalToNoiseRatio = ambientEnvelopeRatio(profile),
    )
    private val tracker = PitchTracker(
        minimumPrimarySalience = profile.minimumPrimarySalience,
        minimumYinConfidenceForOctaveCorrection = MINIMUM_YIN_CONFIDENCE,
        minimumSignalToNoiseRatio = RELATIVE_FLOOR_FRACTION,
        smoothingHalfLifeSeconds = SMOOTHING_HALF_LIFE_SECONDS,
        changeConfirmationSeconds = CHANGE_CONFIRMATION_SECONDS,
        clearAfterSeconds = STALE_AFTER_SECONDS,
        tunedEnterCents = TUNED_ENTER_CENTS,
        tunedExitCents = TUNED_EXIT_CENTS,
    )
    private val elapsedSeconds = hopSize.toDouble() / sampleRate

    /**
     * Active sensitivity. Stored volatile because the UI sets it from the main
     * thread while the audio worker analyses; every frame takes one snapshot and
     * derives its gates from it, so the worker never observes a half-applied
     * profile. Everything else in the engine stays single-consumer.
     */
    @Volatile
    var profile: SensitivityProfile = profile
        private set

    private var lastReading: TunerReading? = null
    private var staleSeconds = 0.0

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(frameSize > 1) { "frameSize must be greater than one" }
        require(hopSize > 0) { "hopSize must be positive" }
        require(maxFrequencyHz <= sampleRate / 2.0) { "search range exceeds Nyquist frequency" }
    }

    /** Applies the new sensitivity immediately to every following frame. */
    fun setProfile(profile: SensitivityProfile) {
        this.profile = profile
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
        lastReading = null
        staleSeconds = 0.0
    }

    private fun analyze(frame: FloatArray): TunerReading? {
        val active = profile
        applyProfile(active)

        // Raw-domain level: measured on the DC-removed capture frame BEFORE any
        // normalisation. Every gate compares acoustic levels here, so the boost
        // applied to the analysis buffer can never raise a candidate past a
        // threshold that the raw microphone never reached.
        val raw = SignalMetrics.analyze(frame, dcRemoved)
        val rawRms = raw.rms
        val clipped = raw.clipped

        // Normalisation feeds salience and YIN only; it never touches a gate.
        normalizer.normalize(dcRemoved, frameSize)
        val proposal = salienceDetector.propose(dcRemoved, sampleRate)
        // A dominant partial clearly outside [min, max] is a real out-of-range
        // tone, not ambience: abstain rather than clamp or fold it into a
        // fabricated in-range note.
        val rejectedRange = isClearlyOutOfRange(salienceDetector.dominantPeakFrequencyHz)
        val yin = if (rejectedRange) null else yinDetector.analyze(dcRemoved, sampleRate)
        val candidateFrequencyHz = proposal?.frequencyHz
        val normalizedSalience = normalizeSalience(proposal?.salience ?: 0.0, rmsOf(dcRemoved))
        val hasEvidence = !rejectedRange && !clipped &&
            satisfiesLevelGates(active, rawRms, candidateFrequencyHz) &&
            normalizedSalience >= active.minimumPrimarySalience &&
            (yin?.confidence ?: 0.0) >= MINIMUM_RELIABLE_YIN_CONFIDENCE

        // The tracker and the gate use the floor from before this observation.
        // Only afterward may a frame with no accepted pitch teach the ambient
        // level, so a sustained tone can never drag the floor up to itself.
        val tracked = if (rejectedRange) {
            null
        } else {
            tracker.update(
                PitchTrackerFrame(
                    primaryFrequencyHz = proposal?.frequencyHz,
                    primarySalience = normalizedSalience,
                    yinFrequencyHz = yin?.frequencyHz,
                    yinConfidence = yin?.confidence ?: 0.0,
                    rms = rawRms,
                    noiseRms = noiseFloor.noiseRms,
                    isClipped = clipped,
                ),
                elapsedSeconds,
            )
        }
        noiseFloor.observe(
            rms = rawRms,
            elapsedSeconds = elapsedSeconds,
            hasReliablePitch = hasEvidence,
            isClipped = clipped,
        )

        if (hasEvidence && tracked != null) {
            val note = NoteMapper.map(tracked.frequencyHz)
            if (note != null) {
                staleSeconds = 0.0
                return TunerReading(
                    noteName = note.noteName,
                    octave = note.octave,
                    frequencyHz = tracked.frequencyHz,
                    cents = tracked.cents,
                    confidence = tracked.confidence.coerceIn(0.0, 1.0),
                    isTuned = tracked.isTuned,
                    isLive = true,
                    rms = rawRms,
                    clipped = clipped,
                ).also { lastReading = it }
            }
        }
        return holdLastReading()
    }

    /**
     * Absolute activation gates, all in the raw domain.
     *
     * A frame must reach the profile's absolute activation threshold
     * [SensitivityProfile.minimumNoteRms] — the single "how quiet may a note be"
     * knob for every frequency, so the sensitivity slider works below 150 Hz
     * too — and still stand above a small [RELATIVE_FLOOR_FRACTION] of the
     * ambient estimate.
     *
     * A quiet candidate below [LOW_BAND_LIMIT_HZ] (where mains hum lives) must
     * additionally prove it is a real string by its harmonic structure; see
     * [isLowBandHumLike]. This replaces the old blunt low-band RMS floor, which
     * forced a soft low note to be played far louder than the profile asked for.
     */
    private fun satisfiesLevelGates(
        active: SensitivityProfile,
        rawRms: Double,
        candidateFrequencyHz: Double?,
    ): Boolean {
        if (rawRms < active.minimumNoteRms) return false
        if (isLowBandHumLike(candidateFrequencyHz, rawRms)) return false
        return rawRms >= noiseFloor.noiseRms * RELATIVE_FLOOR_FRACTION
    }

    /**
     * True when a sub-[LOW_BAND_LIMIT_HZ] candidate looks like continuous mains
     * hum rather than a played note.
     *
     * Mains hum is a nearly pure sine, so it carries almost no energy at 2f or
     * 3f relative to its fundamental; a real string always carries upper
     * harmonics. A candidate is therefore hum-like only while it is quiet (its
     * raw RMS below [CLEARLY_LOUD_RMS]) *and* its measured harmonic support is
     * below [MINIMUM_HARMONIC_SUPPORT]. A clearly loud input skips the check so
     * a genuinely loud low note (or a low test tone) is never rejected for
     * lacking harmonics. The comparison is scale-invariant, so normalisation
     * cannot change the verdict.
     */
    private fun isLowBandHumLike(candidateFrequencyHz: Double?, rawRms: Double): Boolean {
        if (candidateFrequencyHz == null || candidateFrequencyHz >= LOW_BAND_LIMIT_HZ) return false
        if (rawRms >= CLEARLY_LOUD_RMS) return false
        return salienceDetector.harmonicSupportRatio(candidateFrequencyHz, sampleRate) <
            MINIMUM_HARMONIC_SUPPORT
    }

    /**
     * Once a note has been tracked the engine never abstains again: while the
     * current frame still carries acceptable evidence this is a no-op, and after
     * [STALE_AFTER_SECONDS] without any it keeps emitting the last note with
     * `isLive` and `isTuned` both false. A virgin engine stays silent.
     */
    private fun holdLastReading(): TunerReading? {
        val held = lastReading ?: return null
        staleSeconds += elapsedSeconds
        return if (staleSeconds > STALE_AFTER_SECONDS) {
            held.copy(isLive = false, isTuned = false)
        } else {
            held
        }
    }

    private fun applyProfile(active: SensitivityProfile) {
        normalizer.maximumGain = active.maximumInputGain
        tracker.minimumPrimarySalience = active.minimumPrimarySalience
        tracker.minimumSignalToNoiseRatio = RELATIVE_FLOOR_FRACTION
        noiseFloor.minimumNoiseRms = active.minimumNoiseRms
        noiseFloor.minimumSignalToNoiseRatio = ambientEnvelopeRatio(active)
    }

    /**
     * Largest frame-over-floor ratio the ambient estimate may still learn from.
     * It is the profile's own signal-to-noise ratio scaled by the weak
     * [RELATIVE_FLOOR_FRACTION], so it stays below the hum-to-floor ratio of a
     * continuous mains tone and the floor can never climb to the tone itself.
     */
    private fun ambientEnvelopeRatio(active: SensitivityProfile): Double =
        active.minimumSignalToNoiseRatio * RELATIVE_FLOOR_FRACTION

    /**
     * El pico espectral dominante cae claramente fuera de `[min, max]`.
     *
     * Tolerancia del 3 %: un tono exactamente en 40 o 1500 Hz pierde energía a
     * bins vecinos por la ventana Hann y su pico interpolado puede quedar unas
     * centésimas de semitono fuera del borde, así que un margen estrecho
     * mantendría esos tonos límite. Sin esta guarda un tono puro por debajo del
     * rango se recortaría al mínimo y uno por encima se plegaría a un
     * subarmónico mediante la búsqueda de saliencia: se inventaría una nota
     * dentro del rango.
     *
     * Contrapartida conocida: un fundamental dentro del rango cuyo parcial más
     * fuerte esté por encima de 1500 Hz (o por debajo de 40 Hz) también se
     * abstendrá, porque el pico dominante no representa al fundamental. Se
     * prefiere callar antes que arriesgar una lectura falsa.
     */
    private fun isClearlyOutOfRange(dominantPeakHz: Double?): Boolean {
        if (dominantPeakHz == null || !dominantPeakHz.isFinite()) return false
        return dominantPeakHz < minFrequencyHz * (1.0 - OUT_OF_RANGE_TOLERANCE) ||
            dominantPeakHz > maxFrequencyHz * (1.0 + OUT_OF_RANGE_TOLERANCE)
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

    private fun rmsOf(buffer: FloatArray): Double {
        var squaredSum = 0.0
        for (sample in buffer) squaredSum += sample.toDouble() * sample
        return sqrt(squaredSum / buffer.size)
    }

    private companion object {
        const val YIN_CMND_THRESHOLD = 0.15
        const val MINIMUM_RELIABLE_YIN_CONFIDENCE = 0.70
        const val MINIMUM_YIN_CONFIDENCE = 0.80
        const val INITIAL_NOISE_RMS = 0.010
        const val NOISE_HALF_LIFE_SECONDS = 5.0
        const val SALIENCE_REFERENCE_RATIO = 0.25
        const val SMOOTHING_HALF_LIFE_SECONDS = 0.025
        const val CHANGE_CONFIRMATION_SECONDS = 0.080
        const val STALE_AFTER_SECONDS = 0.600
        const val TUNED_ENTER_CENTS = 5.0
        const val TUNED_EXIT_CENTS = 8.0
        const val OUT_OF_RANGE_TOLERANCE = 0.03

        /**
         * A fundamental below this frequency is the band where mains hum lives
         * (50/60/100/120 Hz), well under a guitar's range. A quiet candidate in
         * this band must prove its harmonic structure before it may be
         * reported; see [MINIMUM_HARMONIC_SUPPORT]. Above it, hum is far from
         * the range and the per-profile level knob alone is enough.
         */
        const val LOW_BAND_LIMIT_HZ = 150.0

        /**
         * Raw RMS at and above which a sub-[LOW_BAND_LIMIT_HZ] candidate is
         * treated as clearly loud (about -26 dBFS) and skips the harmonic-support
         * requirement. A note played this hard is a real played note (and the
         * loudest pinned hum fixture is only ~0.014 RMS, ~10.5 dB below this), so
         * the structure check is needed only for the quiet, ambiguous cases.
         */
        const val CLEARLY_LOUD_RMS = 0.05

        /**
         * Least 2f/3f-to-fundamental magnitude ratio a quiet sub-
         * [LOW_BAND_LIMIT_HZ] candidate must show to be accepted.
         *
         * Mains hum is a nearly pure sine: measured 2f/3f leakage is under 0.004
         * for 50/60/100/120 Hz sines at every pinned level. A real harmonic-rich
         * low string measures 0.47-0.57. 0.08 sits an order of magnitude above
         * the hum ceiling and well below a real string, so it separates them by
         * structure instead of by loudness.
         */
        const val MINIMUM_HARMONIC_SUPPORT = 0.08

        /**
         * Weak relative floor veto: a candidate must clear this small fraction
         * of the ambient estimate. It is far looser than the old 3x gate so a
         * quiet sustained tone (whose own level drives the floor) still passes.
         */
        const val RELATIVE_FLOOR_FRACTION = 0.25
    }
}
