package com.cuwano.afinador.tuner

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuwano.afinador.dsp.SensitivityProfile
import com.cuwano.afinador.ui.SensitivityUi
import com.cuwano.afinador.ui.TunerScreen
import com.cuwano.afinador.ui.TunerScreenTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins that the chosen sensitivity level survives leaving and returning, with the
 * real [SharedPreferencesSensitivityStore] and the real [TunerScreen].
 *
 * `MainActivity` is not driven directly on purpose: it polls the session from an
 * endless `LaunchedEffect` every 50 ms and starts real capture, so Compose idling
 * never settles and gestures would be flaky. The compositions below use exactly
 * MainActivity's wiring for this feature -- the stored level is the initial value
 * and every change is written back through the same adapter and preference file --
 * so the behaviour under test is the production one.
 */
@RunWith(AndroidJUnit4::class)
class SensitivityPersistenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val store: SharedPreferencesSensitivityStore
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return SharedPreferencesSensitivityStore(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
        }

    @Test
    fun changingTheLevelThroughTheSliderIsPersisted() {
        store.save(SensitivityProfile.NORMAL)
        val selected = mutableStateOf(store.load())

        composeRule.setContent {
            TunerScreen(
                state = TunerUiState.ListeningSilence,
                sensitivity = selected.value,
                onSensitivityChange = { profile ->
                    selected.value = profile
                    store.save(profile)
                },
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        val target = SensitivityProfile.MUY_ALTA
        composeRule.onNodeWithTag(TunerScreenTags.Sensitivity)
            .performSemanticsAction(SemanticsActions.SetProgress) {
                it(SensitivityUi.indexOf(target).toFloat())
            }

        composeRule.runOnIdle {
            assertEquals(target, selected.value)
            assertEquals("the slider change was not persisted", target, store.load())
        }
    }

    @Test
    fun aFreshCompositionShowsThePreviouslyStoredLevel() {
        store.save(SensitivityProfile.SUAVE)

        // Reading the store is what a restart does: MainActivity applies the stored
        // level before the first capture and the screen renders it.
        val restored = store.load()
        assertEquals(SensitivityProfile.SUAVE, restored)

        composeRule.setContent {
            TunerScreen(
                state = TunerUiState.ListeningSilence,
                sensitivity = restored,
                onSensitivityChange = { store.save(it) },
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithTag(TunerScreenTags.SensitivityLabel)
            .assertIsDisplayed()
            .assertTextEquals(SensitivityUi.label(SensitivityProfile.SUAVE))
    }

    private companion object {
        /** The file MainActivity itself reads and writes. */
        const val PREFERENCES_NAME = "afinador"
    }
}
