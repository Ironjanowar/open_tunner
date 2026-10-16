package com.cuwano.afinador.tuner

import com.cuwano.afinador.dsp.SensitivityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure persistence contract for the sensitivity slider.
 *
 * The encoding is deliberately name based: a stored value that names no known
 * level (a level removed in a later version, or a corrupted preference) must fall
 * back to the factory default instead of throwing on startup.
 */
class SensitivityPreferencesTest {

    @Test
    fun `encode writes the profile name and decode reads it back`() {
        SensitivityProfile.entries.forEach { profile ->
            val encoded = SensitivityPreferences.encode(profile)
            assertEquals(profile.name, encoded)
            assertEquals(profile, SensitivityPreferences.decode(encoded))
        }
    }

    @Test
    fun `a missing stored value decodes to the factory default`() {
        assertEquals(SensitivityProfile.DEFAULT, SensitivityPreferences.decode(null))
    }

    @Test
    fun `a blank stored value decodes to the factory default`() {
        assertEquals(SensitivityProfile.DEFAULT, SensitivityPreferences.decode(""))
        assertEquals(SensitivityProfile.DEFAULT, SensitivityPreferences.decode("   "))
    }

    @Test
    fun `an unknown stored name decodes to the factory default`() {
        assertEquals(SensitivityProfile.DEFAULT, SensitivityPreferences.decode("NivelInexistente"))
        assertEquals(SensitivityProfile.DEFAULT, SensitivityPreferences.decode("MUY_SUAVE_REMOVIDO"))
    }

    @Test
    fun `decode still understands every known level name`() {
        assertEquals(SensitivityProfile.MUY_SUAVE, SensitivityPreferences.decode("MUY_SUAVE"))
        assertEquals(SensitivityProfile.SUAVE, SensitivityPreferences.decode("SUAVE"))
        assertEquals(SensitivityProfile.NORMAL, SensitivityPreferences.decode("NORMAL"))
        assertEquals(SensitivityProfile.ALTA, SensitivityPreferences.decode("ALTA"))
        assertEquals(SensitivityProfile.MUY_ALTA, SensitivityPreferences.decode("MUY_ALTA"))
    }

    @Test
    fun `the preference key is a stable non blank identifier`() {
        assertTrue("the preference key must not be blank", SensitivityPreferences.KEY.isNotBlank())
    }

    @Test
    fun `decode is total so a corrupt preference can never throw on startup`() {
        val hostileStoredValues = listOf<String?>(
            null,
            "",
            "   ",
            "\n\t",
            "42",
            "true",
            "null",
            "muy_alta",
            "ALTA ",
            " MUY_ALTA",
            "NivelInexistente",
            "MUY_ALTA_REMOVIDO",
            "ç",
            "A".repeat(10_000),
        )

        hostileStoredValues.forEach { raw ->
            val decoded = SensitivityPreferences.decode(raw)

            assertTrue(
                "decode(${raw?.take(20)}) returned $decoded, which is not one of the five levels",
                decoded in SensitivityProfile.entries,
            )
        }
    }

    @Test
    fun `the store contract defaults when empty and round trips a saved profile`() {
        val store = InMemorySensitivityStore()

        assertEquals(SensitivityProfile.DEFAULT, store.load())

        store.save(SensitivityProfile.SUAVE)
        assertEquals(SensitivityProfile.SUAVE, store.load())

        store.save(SensitivityProfile.MUY_ALTA)
        assertEquals(SensitivityProfile.MUY_ALTA, store.load())
    }

    /** Documents the [SensitivityStore] contract the shared-preferences adapter must satisfy. */
    private class InMemorySensitivityStore : SensitivityStore {
        private var stored: SensitivityProfile? = null

        override fun load(): SensitivityProfile = stored ?: SensitivityProfile.DEFAULT

        override fun save(profile: SensitivityProfile) {
            stored = profile
        }
    }
}
