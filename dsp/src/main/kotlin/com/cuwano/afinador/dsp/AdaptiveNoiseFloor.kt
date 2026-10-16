package com.cuwano.afinador.dsp

import kotlin.math.pow

data class NoiseGateDecision(
    val signalToNoiseRatio: Double,
    val isOpen: Boolean,
)

/** Tracks ambient RMS with a time-continuous exponential half-life. */
class AdaptiveNoiseFloor(
    private val initialNoiseRms: Double,
    minimumNoiseRms: Double,
    private val halfLifeSeconds: Double,
    minimumSignalToNoiseRatio: Double,
) {
    var noiseRms: Double = initialNoiseRms
        private set

    /**
     * Absolute floor the ambient estimate may not fall below. Mutable so the
     * owning [TunerEngine] can retarget it when the user changes sensitivity;
     * the engine is the single consumer of this object. When the new minimum
     * sits above the current estimate the estimate is lifted to it immediately,
     * so a less sensitive level takes effect on the next frame instead of
     * waiting for the half-life to drag the floor up.
     */
    var minimumNoiseRms: Double = minimumNoiseRms
        set(value) {
            require(value.isFinite() && value > 0.0 && value <= initialNoiseRms) {
                "minimumNoiseRms must be finite, positive, and no greater than initialNoiseRms"
            }
            field = value
            if (noiseRms < value) noiseRms = value
        }

    /** Smallest trusted frame-over-floor ratio; retargeted per sensitivity. */
    var minimumSignalToNoiseRatio: Double = minimumSignalToNoiseRatio
        set(value) {
            require(value.isFinite() && value > 0.0) {
                "minimumSignalToNoiseRatio must be finite and positive"
            }
            field = value
        }

    init {
        require(initialNoiseRms.isFinite() && initialNoiseRms > 0.0) {
            "initialNoiseRms must be finite and positive"
        }
        require(minimumNoiseRms.isFinite() && minimumNoiseRms > 0.0 && minimumNoiseRms <= initialNoiseRms) {
            "minimumNoiseRms must be finite, positive, and no greater than initialNoiseRms"
        }
        require(halfLifeSeconds.isFinite() && halfLifeSeconds > 0.0) {
            "halfLifeSeconds must be finite and positive"
        }
        require(minimumSignalToNoiseRatio.isFinite() && minimumSignalToNoiseRatio > 0.0) {
            "minimumSignalToNoiseRatio must be finite and positive"
        }
    }

    fun evaluate(signalRms: Double): NoiseGateDecision {
        require(signalRms.isFinite() && signalRms >= 0.0) { "signalRms must be finite and non-negative" }
        val ratio = signalRms / noiseRms
        return NoiseGateDecision(ratio, ratio >= minimumSignalToNoiseRatio)
    }

    fun observe(
        rms: Double,
        elapsedSeconds: Double,
        hasReliablePitch: Boolean,
        isClipped: Boolean = false,
    ): NoiseGateDecision {
        require(rms.isFinite() && rms >= 0.0) { "rms must be finite and non-negative" }
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "elapsedSeconds must be finite and non-negative"
        }
        val decision = evaluate(rms)
        // Observations no louder than the gate boundary can teach the ambient
        // level. Louder unknown signals are protected as possible startup notes.
        val canLearnLevel = rms <= noiseRms * minimumSignalToNoiseRatio + LEVEL_EPSILON
        if (!hasReliablePitch && !isClipped && canLearnLevel) {
            val retained = 0.5.pow(elapsedSeconds / halfLifeSeconds)
            noiseRms = (retained * noiseRms + (1.0 - retained) * rms).coerceAtLeast(minimumNoiseRms)
        }
        return if (isClipped) decision.copy(isOpen = false) else decision
    }

    fun reset() {
        noiseRms = initialNoiseRms
    }

    private companion object {
        const val LEVEL_EPSILON = 1e-15
    }
}
