package com.cuwano.afinador.tuner

import android.content.SharedPreferences
import androidx.core.content.edit
import com.cuwano.afinador.dsp.SensitivityProfile

/**
 * Persists the player's chosen detector sensitivity between launches.
 *
 * The contract is deliberately tiny so the pure mapping can be exercised from
 * host tests while the Android adapter stays a thin shell.
 */
interface SensitivityStore {
    fun load(): SensitivityProfile
    fun save(profile: SensitivityProfile)
}

/**
 * Name-based encoding of a [SensitivityProfile].
 *
 * Names, not ordinals: re-ordering or inserting a level must never silently
 * change what a stored value means, and a value that names no known level (a
 * level removed in a later version, or a corrupted preference) falls back to the
 * factory default instead of throwing on startup.
 */
object SensitivityPreferences {
    const val KEY = "sensitivity_profile"

    fun encode(profile: SensitivityProfile): String = profile.name

    fun decode(raw: String?): SensitivityProfile {
        val name = raw?.trim().orEmpty()
        return SensitivityProfile.entries.firstOrNull { it.name == name }
            ?: SensitivityProfile.DEFAULT
    }
}

/** [SensitivityStore] backed by [SharedPreferences]; the caller chooses the file and mode. */
class SharedPreferencesSensitivityStore(
    private val prefs: SharedPreferences,
) : SensitivityStore {

    override fun load(): SensitivityProfile =
        SensitivityPreferences.decode(readStoredString())

    override fun save(profile: SensitivityProfile) {
        prefs.edit { putString(SensitivityPreferences.KEY, SensitivityPreferences.encode(profile)) }
    }

    /**
     * `getString` casts whatever is stored under the key, so a value written by
     * another type (a foreign/legacy writer, a corrupted file) makes it throw
     * `ClassCastException`. Losing the sensitivity preference is acceptable;
     * failing to start the tuner is not.
     */
    private fun readStoredString(): String? =
        runCatching { prefs.getString(SensitivityPreferences.KEY, null) }.getOrNull()
}
