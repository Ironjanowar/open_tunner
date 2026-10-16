package com.cuwano.afinador.ui

import com.cuwano.afinador.dsp.SensitivityProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure mapping between the five [SensitivityProfile] levels and the
 * labelled, snapping slider positions the screen renders.
 */
class SensitivityUiTest {

    @Test
    fun `levels expose exactly the five profiles in sensitivity order`() {
        assertEquals(
            listOf(
                SensitivityProfile.MUY_SUAVE,
                SensitivityProfile.SUAVE,
                SensitivityProfile.NORMAL,
                SensitivityProfile.ALTA,
                SensitivityProfile.MUY_ALTA,
            ),
            SensitivityUi.levels,
        )
    }

    @Test
    fun `labels are the Spanish wording in the same order as the levels`() {
        assertEquals(
            listOf("Muy suave", "Suave", "Normal", "Alta", "Muy alta"),
            SensitivityUi.labels,
        )
        assertEquals(SensitivityUi.levels.size, SensitivityUi.labels.size)
    }

    @Test
    fun `label maps every profile to its Spanish name`() {
        assertEquals("Muy suave", SensitivityUi.label(SensitivityProfile.MUY_SUAVE))
        assertEquals("Suave", SensitivityUi.label(SensitivityProfile.SUAVE))
        assertEquals("Normal", SensitivityUi.label(SensitivityProfile.NORMAL))
        assertEquals("Alta", SensitivityUi.label(SensitivityProfile.ALTA))
        assertEquals("Muy alta", SensitivityUi.label(SensitivityProfile.MUY_ALTA))
    }

    @Test
    fun `index label and profile round trip for every level`() {
        SensitivityUi.levels.forEachIndexed { index, profile ->
            assertEquals(index, SensitivityUi.indexOf(profile))
            assertEquals(profile, SensitivityUi.profileAt(index.toFloat()))
            assertEquals(SensitivityUi.labels[index], SensitivityUi.label(profile))
        }
    }

    @Test
    fun `the factory default sits on a snapping position`() {
        val index = SensitivityUi.indexOf(SensitivityProfile.DEFAULT)
        assertEquals(SensitivityProfile.DEFAULT, SensitivityUi.profileAt(index.toFloat()))
    }

    @Test
    fun `profileAt snaps to the nearest level`() {
        assertEquals(SensitivityProfile.MUY_SUAVE, SensitivityUi.profileAt(0.4f))
        assertEquals(SensitivityProfile.SUAVE, SensitivityUi.profileAt(0.6f))
        assertEquals(SensitivityProfile.NORMAL, SensitivityUi.profileAt(2.49f))
        assertEquals(SensitivityProfile.ALTA, SensitivityUi.profileAt(2.51f))
        assertEquals(SensitivityProfile.MUY_ALTA, SensitivityUi.profileAt(4f))
    }

    @Test
    fun `profileAt clamps out of range indices to the first and last level`() {
        assertEquals(SensitivityProfile.MUY_SUAVE, SensitivityUi.profileAt(-5f))
        assertEquals(SensitivityProfile.MUY_SUAVE, SensitivityUi.profileAt(-0.4f))
        assertEquals(SensitivityProfile.MUY_ALTA, SensitivityUi.profileAt(4.4f))
        assertEquals(SensitivityProfile.MUY_ALTA, SensitivityUi.profileAt(99f))
    }

    @Test
    fun `three slider steps expose exactly five snapping positions`() {
        assertEquals(3, SensitivityUi.sliderSteps)
        assertEquals(
            "five levels need exactly three steps between them",
            SensitivityUi.levels.size,
            SensitivityUi.sliderSteps + 2,
        )
    }
}
