package com.cuwano.afinador.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Confirms the five user-selectable sensitivity levels and their ordering.
 *
 * A more sensitive level must loosen every gate in the same direction: a lower
 * signal-to-noise requirement, a lower primary-salience requirement, a higher
 * normalisation gain cap and a lower noise floor. That monotonicity is what
 * makes the levels predictable for the user instead of five unrelated presets.
 */
class SensitivityProfileTest {
    @Test
    fun `five profiles expose the confirmed parameter values`() {
        val expected = linkedMapOf(
            SensitivityProfile.MUY_SUAVE to listOf(5.0, 0.50, 3.0, 0.004, 0.030),
            SensitivityProfile.SUAVE to listOf(3.5, 0.42, 5.0, 0.003, 0.020),
            SensitivityProfile.NORMAL to listOf(2.0, 0.35, 8.0, 0.002, 0.012),
            SensitivityProfile.ALTA to listOf(1.5, 0.30, 12.0, 0.0012, 0.006),
            SensitivityProfile.MUY_ALTA to listOf(1.2, 0.26, 16.0, 0.0008, 0.003),
        )

        assertEquals(5, SensitivityProfile.entries.size, "expected exactly five sensitivity levels")
        expected.forEach { (profile, values) ->
            assertEquals(values[0], profile.minimumSignalToNoiseRatio, "$profile snr")
            assertEquals(values[1], profile.minimumPrimarySalience, "$profile salience")
            assertEquals(values[2], profile.maximumInputGain, "$profile gain")
            assertEquals(values[3], profile.minimumNoiseRms, "$profile noise floor")
            assertEquals(values[4], profile.minimumNoteRms, "$profile note threshold")
        }
    }

    @Test
    fun `default profile is alta`() {
        assertSame(SensitivityProfile.ALTA, SensitivityProfile.DEFAULT)
    }

    @Test
    fun `each more sensitive level is monotonic in every gated parameter`() {
        val leastToMost = listOf(
            SensitivityProfile.MUY_SUAVE,
            SensitivityProfile.SUAVE,
            SensitivityProfile.NORMAL,
            SensitivityProfile.ALTA,
            SensitivityProfile.MUY_ALTA,
        )

        leastToMost.zipWithNext().forEach { (lessSensitive, moreSensitive) ->
            assertTrue(
                moreSensitive.minimumSignalToNoiseRatio < lessSensitive.minimumSignalToNoiseRatio,
                "snr must fall from $lessSensitive to $moreSensitive",
            )
            assertTrue(
                moreSensitive.minimumPrimarySalience < lessSensitive.minimumPrimarySalience,
                "salience must fall from $lessSensitive to $moreSensitive",
            )
            assertTrue(
                moreSensitive.maximumInputGain > lessSensitive.maximumInputGain,
                "gain cap must rise from $lessSensitive to $moreSensitive",
            )
            assertTrue(
                moreSensitive.minimumNoiseRms < lessSensitive.minimumNoiseRms,
                "noise floor must fall from $lessSensitive to $moreSensitive",
            )
            assertTrue(
                moreSensitive.minimumNoteRms < lessSensitive.minimumNoteRms,
                "note threshold must fall from $lessSensitive to $moreSensitive",
            )
        }
    }

    @Test
    fun `every profile value is finite positive and the gain cap exceeds unity`() {
        SensitivityProfile.entries.forEach { profile ->
            assertTrue(
                profile.minimumSignalToNoiseRatio.isFinite() && profile.minimumSignalToNoiseRatio > 0.0,
                "$profile snr must be finite and positive",
            )
            assertTrue(
                profile.minimumPrimarySalience.isFinite() &&
                    profile.minimumPrimarySalience > 0.0 &&
                    profile.minimumPrimarySalience <= 1.0,
                "$profile salience must be a finite fraction",
            )
            assertTrue(
                profile.maximumInputGain.isFinite() && profile.maximumInputGain > 1.0,
                "$profile gain cap must be finite and greater than one",
            )
            assertTrue(
                profile.minimumNoiseRms.isFinite() && profile.minimumNoiseRms > 0.0,
                "$profile noise floor must be finite and positive",
            )
            assertTrue(
                profile.minimumNoteRms.isFinite() && profile.minimumNoteRms > 0.0,
                "$profile note threshold must be finite and positive",
            )
        }
    }
}
