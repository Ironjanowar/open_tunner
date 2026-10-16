package com.cuwano.afinador.tuner

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuwano.afinador.dsp.SensitivityProfile
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real shared-preferences adapter must never crash on a value it cannot read.
 *
 * `SharedPreferences.getString` casts whatever is stored under the key, so a value
 * written with another type (a foreign/legacy writer, a corrupted file) makes it
 * throw `ClassCastException`. Losing the sensitivity preference is acceptable;
 * failing to start the tuner is not.
 */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesSensitivityStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: SharedPreferencesSensitivityStore

    @Before
    fun useAFreshPreferencesFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SharedPreferencesSensitivityStore(prefs)
    }

    @Test
    fun load_fallsBackToTheFactoryDefaultWhenTheStoredValueIsNotAString() {
        // Same key, different type: getString would throw ClassCastException.
        prefs.edit().putInt(SensitivityPreferences.KEY, 7).commit()

        assertEquals(SensitivityProfile.DEFAULT, store.load())
    }

    @Test
    fun load_fallsBackToTheFactoryDefaultForABooleanStoredValue() {
        prefs.edit().putBoolean(SensitivityPreferences.KEY, true).commit()

        assertEquals(SensitivityProfile.DEFAULT, store.load())
    }

    @Test
    fun save_roundTripsAProfileAndOverwritesAForeignValue() {
        store.save(SensitivityProfile.MUY_ALTA)
        assertEquals(SensitivityProfile.MUY_ALTA, store.load())

        prefs.edit().putBoolean(SensitivityPreferences.KEY, true).commit()
        assertEquals(SensitivityProfile.DEFAULT, store.load())

        store.save(SensitivityProfile.SUAVE)
        assertEquals(SensitivityProfile.SUAVE, store.load())
    }

    private companion object {
        const val PREFERENCES_NAME = "afinador_sensitivity_store_test"
    }
}
