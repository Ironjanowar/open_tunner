package com.cuwano.afinador.ui

import com.cuwano.afinador.dsp.SensitivityProfile
import kotlin.math.roundToInt

/**
 * Pure mapping between the five [SensitivityProfile] levels and the labelled,
 * snapping slider positions the screen renders.
 *
 * The slider moves over indices `0..levels.lastIndex`; [sliderSteps] is the
 * number of positions *between* the ends, so five levels need exactly three.
 */
object SensitivityUi {
    val levels: List<SensitivityProfile> = listOf(
        SensitivityProfile.MUY_SUAVE,
        SensitivityProfile.SUAVE,
        SensitivityProfile.NORMAL,
        SensitivityProfile.ALTA,
        SensitivityProfile.MUY_ALTA,
    )

    val labels: List<String> = listOf("Muy suave", "Suave", "Normal", "Alta", "Muy alta")

    val sliderSteps: Int = levels.size - 2

    fun label(profile: SensitivityProfile): String = labels[indexOf(profile)]

    fun indexOf(profile: SensitivityProfile): Int = levels.indexOf(profile)

    /** Rounds an arbitrary slider position to the nearest level, clamping out-of-range input. */
    fun profileAt(index: Float): SensitivityProfile =
        levels[index.roundToInt().coerceIn(0, levels.lastIndex)]
}
