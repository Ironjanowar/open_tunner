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
 * The activation thresholds sit 9.4x to 15x lower than the first release
 * (19.4-23.5 dB), while the gain caps rise by 1x to 3x depending on the level.
 * Field use showed that an unplugged electric guitar and a softly played
 * acoustic instrument were inaudible even at the top level. Thresholds checked
 * with a synthetic harmonic string in a quiet room: MUY_SUAVE -49.9 dBFS, SUAVE
 * -55.9 dBFS, NORMAL -61.9 dBFS, ALTA -68.0 dBFS, MUY_ALTA -74.0 dBFS.
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
    MUY_SUAVE(5.0, 0.50, 3.0, 0.0007, 0.0032),
    SUAVE(3.5, 0.42, 6.0, 0.00055, 0.0016),
    NORMAL(2.0, 0.35, 12.0, 0.0004, 0.0008),
    ALTA(1.5, 0.30, 24.0, 0.00025, 0.0004),
    MUY_ALTA(1.2, 0.26, 48.0, 0.00012, 0.0002),
    ;

    companion object {
        /** Balanced default that detects quiet notes without chasing noise. */
        val DEFAULT: SensitivityProfile = ALTA
    }
}
