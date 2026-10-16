package com.cuwano.afinador.dsp

/** Immutable value emitted for an analyzed frame with a stable pitch. */
data class TunerReading(
    val noteName: String,
    val octave: Int,
    val frequencyHz: Double,
    val cents: Double,
    val confidence: Double,
    val isTuned: Boolean,
    val isLive: Boolean = true,
    val rms: Double,
    val clipped: Boolean,
)
