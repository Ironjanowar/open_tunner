package com.cuwano.afinador.tuner

import com.cuwano.afinador.dsp.TunerReading
import java.util.Locale

sealed interface TunerUiState {
    data object PermissionNeeded : TunerUiState

    data object PermissionDeniedPermanently : TunerUiState

    data object ListeningSilence : TunerUiState

    data class Reading(
        val noteName: String,
        val octave: Int,
        val cents: Double,
        val frequencyHz: Double,
        val confidence: Double,
        val isTuned: Boolean,
        val isLive: Boolean = true,
    ) : TunerUiState {
        val noteWithOctave: String = "$noteName$octave"
        val centsText: String = String.format(Locale.ROOT, "%+.1f cents", cents)
        val frequencyText: String = String.format(Locale.ROOT, "%.2f Hz", frequencyHz)
        val confidenceText: String = String.format(Locale.ROOT, "%.0f %%", confidence * 100.0)

        /** A held note: capture stopped or the detector went quiet, so the values are the last ones seen. */
        val isStale: Boolean = !isLive

        /** The single source of truth for the wording the status pill shows. */
        val statusText: String = when {
            isStale -> "Última nota"
            isTuned -> "Afinado"
            cents > 0.0 -> "Demasiado alto"
            else -> "Demasiado bajo"
        }
    }

    data class AudioError(val message: String) : TunerUiState
}

object TunerUiStateReducer {
    fun permissionNeeded(permanentlyDenied: Boolean): TunerUiState =
        if (permanentlyDenied) {
            TunerUiState.PermissionDeniedPermanently
        } else {
            TunerUiState.PermissionNeeded
        }

    fun listening(reading: TunerReading?): TunerUiState = reading?.let {
        TunerUiState.Reading(
            noteName = it.noteName,
            octave = it.octave,
            cents = it.cents,
            frequencyHz = it.frequencyHz,
            confidence = it.confidence,
            isTuned = it.isTuned,
            isLive = it.isLive,
        )
    } ?: TunerUiState.ListeningSilence

    fun audioError(message: String): TunerUiState = TunerUiState.AudioError(message)
}
