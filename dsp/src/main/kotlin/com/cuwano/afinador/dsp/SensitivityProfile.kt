package com.cuwano.afinador.dsp

/**
 * User-selectable detector sensitivity.
 *
 * A more sensitive level loosens every gate in the same direction: a lower
 * signal-to-noise requirement, a lower primary-salience requirement, a higher
 * normalisation gain cap, a lower absolute noise floor and a lower absolute
 * activation threshold. That monotonicity is what makes the five levels
 * predictable for the player instead of five unrelated presets.
 *
 * @param minimumSignalToNoiseRatio smallest frame RMS over the ambient floor
 *   that may still be trusted as a tone. The engine scales it by the weak
 *   relative floor fraction to size the noise floor's ambient envelope.
 * @param minimumPrimarySalience smallest normalised harmonic salience that may
 *   still be accepted as a note.
 * @param maximumInputGain largest linear gain the input normaliser may apply.
 * @param minimumNoiseRms absolute floor the adaptive ambient estimate may not
 *   fall below, so digital silence cannot make the gate hair-triggered.
 * @param minimumNoteRms absolute activation threshold: the raw (pre-
 *   normalisation) frame RMS a frame must reach before it may be accepted as a
 *   note. This is the real "how quiet a note may be" knob — a quieter level
 *   raises it, so a soft note is heard only by the more sensitive levels.
 */
enum class SensitivityProfile(
    val minimumSignalToNoiseRatio: Double,
    val minimumPrimarySalience: Double,
    val maximumInputGain: Double,
    val minimumNoiseRms: Double,
    val minimumNoteRms: Double,
) {
    MUY_SUAVE(5.0, 0.50, 3.0, 0.004, 0.030),
    SUAVE(3.5, 0.42, 5.0, 0.003, 0.020),
    NORMAL(2.0, 0.35, 8.0, 0.002, 0.012),
    ALTA(1.5, 0.30, 12.0, 0.0012, 0.006),
    MUY_ALTA(1.2, 0.26, 16.0, 0.0008, 0.003),
    ;

    companion object {
        /** Balanced default that detects quiet notes without chasing noise. */
        val DEFAULT: SensitivityProfile = ALTA
    }
}
