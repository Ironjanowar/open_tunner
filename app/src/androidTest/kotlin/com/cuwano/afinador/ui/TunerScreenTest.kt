package com.cuwano.afinador.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.cuwano.afinador.dsp.SensitivityProfile
import com.cuwano.afinador.tuner.TunerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TunerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reading_usesSpanish_darkSurface_andCentersTheNote() {
        composeRule.setContent {
            TunerScreen(
                state = reading(),
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Afinador cromático").assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.Note).assertTextEquals("A", "4")
        composeRule.onNodeWithText("Afinado").assertIsDisplayed()

        val root = composeRule.onNodeWithTag(TunerScreenTags.Root).fetchSemanticsNode()
        val note = composeRule.onNodeWithTag(TunerScreenTags.Note).fetchSemanticsNode()
        assertEquals(root.boundsInRoot.center.x, note.boundsInRoot.center.x, 2f)

        val corner = composeRule.onNodeWithTag(TunerScreenTags.Root)
            .captureToImage()
            .toPixelMap()[0, 0]
        assertTrue("The screen surface must be dark", corner.luminance() < 0.25f)
    }

    @Test
    fun gauge_clampsVisualSemantics_butKeepsRawCentsText() {
        composeRule.setContent {
            TunerScreen(
                state = reading(cents = 73.0, isTuned = false),
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("+73.0 cents").assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.CentsGauge).assert(
            hasProgressBarRangeInfo(ProgressBarRangeInfo(50f, -50f..50f)),
        )
    }

    @Test
    fun gauge_clampsNegativeVisualSemantics_butKeepsRawCentsText() {
        composeRule.setContent {
            TunerScreen(
                state = reading(cents = -64.5, isTuned = false),
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("-64.5 cents").assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.CentsGauge).assert(
            hasProgressBarRangeInfo(ProgressBarRangeInfo(-50f, -50f..50f)),
        )
    }

    @Test
    fun silence_promptsForASustainedNote_andDoesNotShowAStaleGauge() {
        composeRule.setContent {
            TunerScreen(
                state = TunerUiState.ListeningSilence,
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Toca una nota sostenida").assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.CentsGauge).assertDoesNotExist()
    }

    @Test
    fun permissionNeeded_exposesAnAccessibleRequestAction() {
        var requests = 0
        composeRule.setContent {
            TunerScreen(
                state = TunerUiState.PermissionNeeded,
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = { requests++ },
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Se necesita acceso al micrófono").assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.PermissionAction)
            .assertTextEquals("Permitir micrófono")
            .assertContentDescriptionEquals("Solicitar permiso del micrófono")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun permanentlyDeniedPermission_opensSettingsInsteadOfRequestingAgain() {
        var requests = 0
        var settingsOpens = 0
        composeRule.setContent {
            TunerScreen(
                state = TunerUiState.PermissionDeniedPermanently,
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = { requests++ },
                onOpenSettings = { settingsOpens++ },
            )
        }

        composeRule.onNodeWithText("El permiso del micrófono está desactivado").assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.PermissionAction)
            .assertTextEquals("Abrir ajustes")
            .assertContentDescriptionEquals("Abrir ajustes de la aplicación")
            .performClick()
        composeRule.runOnIdle {
            assertEquals(0, requests)
            assertEquals(1, settingsOpens)
        }
    }

    @Test
    fun audioError_isPresentedInSpanish_withItsDiagnostic() {
        composeRule.setContent {
            TunerScreen(
                state = TunerUiState.AudioError("No se pudo iniciar el micrófono"),
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Error de audio").assertIsDisplayed()
        composeRule.onNodeWithText("No se pudo iniciar el micrófono").assertIsDisplayed()
    }

    @Test
    fun sensitivitySlider_labelsTheFactoryDefault() {
        composeRule.setContent {
            TunerScreen(
                state = reading(),
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithTag(TunerScreenTags.Sensitivity).assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.SensitivityLabel).assertTextEquals("Alta")
    }

    @Test
    fun sensitivitySlider_snapsToALevel_andReportsTheSelectedProfile() {
        val selected = mutableStateOf(SensitivityProfile.DEFAULT)
        val changes = mutableListOf<SensitivityProfile>()
        composeRule.setContent {
            TunerScreen(
                state = reading(),
                sensitivity = selected.value,
                onSensitivityChange = {
                    changes += it
                    selected.value = it
                },
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        val target = SensitivityProfile.MUY_ALTA
        val targetIndex = SensitivityUi.indexOf(target)
        composeRule.onNodeWithTag(TunerScreenTags.Sensitivity)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(targetIndex.toFloat()) }

        composeRule.runOnIdle { assertEquals(listOf(target), changes) }
        composeRule.onNodeWithTag(TunerScreenTags.SensitivityLabel).assertTextEquals("Muy alta")
    }

    @Test
    fun sensitivitySlider_showsAllFiveLevelNames_andMarksTheSelectedOne() {
        composeRule.setContent {
            TunerScreen(
                state = reading(),
                sensitivity = SensitivityProfile.NORMAL,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        // Every level must be readable on screen, not only the selected one.
        SensitivityUi.labels.forEachIndexed { index, label ->
            composeRule.onNodeWithTag("SensitivityTick_$index").assertTextEquals(label)
        }

        // Exactly one tick is the selected level, and it is the one tagged
        // SensitivityLabel, so the player can tell the chosen level from the rest.
        val selectedIndex = SensitivityUi.indexOf(SensitivityProfile.NORMAL)
        composeRule.onNodeWithTag(TunerScreenTags.SensitivityLabel)
            .assertTextEquals(SensitivityUi.labels[selectedIndex])
        assertEquals(
            "exactly one tick must be tagged as the selected level",
            1,
            composeRule.onAllNodesWithTag(TunerScreenTags.SensitivityLabel).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun staleReading_showsUltimaNotaPill_andKeepsTheNoteOnScreen() {
        composeRule.setContent {
            TunerScreen(
                state = reading(isLive = false),
                sensitivity = SensitivityProfile.DEFAULT,
                onSensitivityChange = {},
                onRequestPermission = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Última nota").assertIsDisplayed()
        composeRule.onNodeWithTag(TunerScreenTags.Note).assertTextEquals("A", "4")
    }

    private fun reading(
        cents: Double = 0.0,
        isTuned: Boolean = true,
        isLive: Boolean = true,
    ) = TunerUiState.Reading(
        noteName = "A",
        octave = 4,
        cents = cents,
        frequencyHz = 440.0,
        confidence = 0.98,
        isTuned = isTuned,
        isLive = isLive,
    )
}
