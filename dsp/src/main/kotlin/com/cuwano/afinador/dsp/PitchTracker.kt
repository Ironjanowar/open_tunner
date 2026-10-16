package com.cuwano.afinador.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class PitchTrackerFrame(
    val primaryFrequencyHz: Double?,
    val primarySalience: Double,
    val yinFrequencyHz: Double?,
    val yinConfidence: Double,
    val rms: Double,
    val noiseRms: Double,
    val isClipped: Boolean = false,
)

data class TrackedPitch(
    val frequencyHz: Double,
    val cents: Double,
    val confidence: Double,
    val isTuned: Boolean,
)

/** Applies quality gates, octave correction, smoothing, expiry, and tuned hysteresis. */
class PitchTracker(
    private val minimumPrimarySalience: Double,
    private val minimumYinConfidenceForOctaveCorrection: Double,
    private val minimumSignalToNoiseRatio: Double,
    private val smoothingHalfLifeSeconds: Double,
    private val changeConfirmationSeconds: Double,
    private val clearAfterSeconds: Double,
    private val tunedEnterCents: Double,
    private val tunedExitCents: Double,
) {
    var current: TrackedPitch? = null
        private set

    private var weakSeconds = 0.0
    private var tuned = false
    private val recentLogFrequencies = ArrayDeque<Double>(MEDIAN_WINDOW_SIZE)
    private var smoothedLogFrequency: Double? = null
    private var pendingLogFrequency: Double? = null
    private var pendingSeconds = 0.0

    init {
        require(minimumPrimarySalience.isFinite() && minimumPrimarySalience in 0.0..1.0)
        require(minimumYinConfidenceForOctaveCorrection.isFinite() && minimumYinConfidenceForOctaveCorrection in 0.0..1.0)
        require(minimumSignalToNoiseRatio.isFinite() && minimumSignalToNoiseRatio > 0.0)
        require(smoothingHalfLifeSeconds.isFinite() && smoothingHalfLifeSeconds > 0.0)
        require(changeConfirmationSeconds.isFinite() && changeConfirmationSeconds >= 0.0)
        require(clearAfterSeconds.isFinite() && clearAfterSeconds >= 0.0)
        require(tunedEnterCents.isFinite() && tunedEnterCents >= 0.0)
        require(tunedExitCents.isFinite() && tunedExitCents >= tunedEnterCents)
    }

    fun update(frame: PitchTrackerFrame, elapsedSeconds: Double): TrackedPitch? {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "elapsedSeconds must be finite and non-negative"
        }
        validate(frame)
        val primary = reliablePrimary(frame) ?: return holdOrClear(elapsedSeconds)
        val selected = correctedFrequency(primary, frame)
        val stabilizedLog = stabilize(ln(selected))
        val smoothed = smoothOrConfirm(stabilizedLog, elapsedSeconds)
        val mapping = NoteMapper.map(smoothed) ?: return holdOrClear(elapsedSeconds)

        weakSeconds = 0.0
        val absoluteCents = abs(mapping.cents)
        tuned = if (tuned) {
            absoluteCents <= tunedExitCents + CENTS_BOUNDARY_EPSILON
        } else {
            absoluteCents <= tunedEnterCents + CENTS_BOUNDARY_EPSILON
        }
        val confidence = relevantConfidence(primary, selected, frame)
        return TrackedPitch(smoothed, mapping.cents, confidence, tuned).also { current = it }
    }

    fun reset() {
        current = null
        weakSeconds = 0.0
        tuned = false
        recentLogFrequencies.clear()
        smoothedLogFrequency = null
        pendingLogFrequency = null
        pendingSeconds = 0.0
    }

    private fun reliablePrimary(frame: PitchTrackerFrame): Double? {
        if (frame.isClipped) return null
        val frequency = frame.primaryFrequencyHz ?: return null
        if (!frequency.isFinite() || frequency <= 0.0) return null
        if (frame.primarySalience < minimumPrimarySalience) return null
        if (frame.noiseRms <= 0.0 || frame.rms / frame.noiseRms < minimumSignalToNoiseRatio) return null
        return frequency
    }

    private fun correctedFrequency(primary: Double, frame: PitchTrackerFrame): Double {
        val yin = frame.yinFrequencyHz ?: return primary
        if (!yin.isFinite() || yin <= 0.0) return primary
        if (frame.yinConfidence < minimumYinConfidenceForOctaveCorrection) return primary
        val octaveDistance = abs(ln(primary / yin) / LN_2)
        return if (abs(octaveDistance - 1.0) <= OCTAVE_TOLERANCE) yin else primary
    }

    private fun holdOrClear(elapsedSeconds: Double): TrackedPitch? {
        if (current == null) return null
        weakSeconds += elapsedSeconds
        if (weakSeconds > clearAfterSeconds) reset()
        return current
    }

    /** Median only at a one-frame reversal, avoiding latency during real motion. */
    private fun stabilize(logFrequency: Double): Double {
        recentLogFrequencies.addLast(logFrequency)
        if (recentLogFrequencies.size > MEDIAN_WINDOW_SIZE) recentLogFrequencies.removeFirst()
        if (recentLogFrequencies.size < MEDIAN_WINDOW_SIZE) return logFrequency
        val first = recentLogFrequencies[0]
        val middle = recentLogFrequencies[1]
        val last = recentLogFrequencies[2]
        if ((middle - first) * (last - middle) >= 0.0) return last
        return listOf(first, middle, last).sorted()[1]
    }

    private fun smoothOrConfirm(candidateLog: Double, elapsedSeconds: Double): Double {
        val previousLog = smoothedLogFrequency
        if (previousLog == null) {
            smoothedLogFrequency = candidateLog
            return exp(candidateLog)
        }

        if (centsBetweenLogs(previousLog, candidateLog) > CHANGE_THRESHOLD_CENTS && changeConfirmationSeconds > 0.0) {
            val pending = pendingLogFrequency
            val continuesCoherentMotion = pending != null &&
                (pending - previousLog) * (candidateLog - pending) > 0.0
            if (pending == null ||
                (centsBetweenLogs(pending, candidateLog) > CHANGE_CLUSTER_CENTS && !continuesCoherentMotion)
            ) {
                pendingLogFrequency = candidateLog
                pendingSeconds = elapsedSeconds
            } else {
                pendingLogFrequency = candidateLog
                pendingSeconds += elapsedSeconds
            }
            if (pendingSeconds + TIME_EPSILON < changeConfirmationSeconds) return exp(previousLog)
            smoothedLogFrequency = candidateLog
            pendingLogFrequency = null
            pendingSeconds = 0.0
            return exp(candidateLog)
        }

        pendingLogFrequency = null
        pendingSeconds = 0.0
        val retained = 0.5.pow(elapsedSeconds / smoothingHalfLifeSeconds)
        val nextLog = retained * previousLog + (1.0 - retained) * candidateLog
        smoothedLogFrequency = nextLog
        return exp(nextLog)
    }

    private fun relevantConfidence(primary: Double, selected: Double, frame: PitchTrackerFrame): Double {
        val yin = frame.yinFrequencyHz
        val yinIsRelevant = yin != null && yin.isFinite() && yin > 0.0 &&
            frame.yinConfidence >= minimumYinConfidenceForOctaveCorrection &&
            (centsBetweenFrequencies(selected, yin) <= AGREEMENT_CENTS || selected != primary)
        return if (yinIsRelevant) {
            ((frame.primarySalience + frame.yinConfidence) / 2.0).coerceIn(0.0, 1.0)
        } else {
            frame.primarySalience
        }
    }

    private fun centsBetweenLogs(first: Double, second: Double): Double =
        abs((second - first) * CENTS_PER_OCTAVE / LN_2)

    private fun centsBetweenFrequencies(first: Double, second: Double): Double =
        centsBetweenLogs(ln(first), ln(second))

    private fun validate(frame: PitchTrackerFrame) {
        require(frame.primarySalience.isFinite() && frame.primarySalience in 0.0..1.0)
        require(frame.yinConfidence.isFinite() && frame.yinConfidence in 0.0..1.0)
        require(frame.rms.isFinite() && frame.rms >= 0.0)
        require(frame.noiseRms.isFinite() && frame.noiseRms >= 0.0)
    }

    private companion object {
        const val OCTAVE_TOLERANCE = 0.08
        const val CENTS_BOUNDARY_EPSILON = 1e-9
        const val MEDIAN_WINDOW_SIZE = 3
        const val CHANGE_THRESHOLD_CENTS = 50.0
        const val CHANGE_CLUSTER_CENTS = 50.0
        const val AGREEMENT_CENTS = 50.0
        const val CENTS_PER_OCTAVE = 1200.0
        const val LN_2 = 0.6931471805599453
        const val TIME_EPSILON = 1e-12
    }
}
