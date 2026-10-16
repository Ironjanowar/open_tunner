package com.cuwano.afinador.dsp

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

data class NoteMapping(
    val noteName: String,
    val octave: Int,
    val targetFrequencyHz: Double,
    val cents: Double,
)

object NoteMapper {
    private const val MIN_FREQUENCY_HZ = 40.0
    private const val MAX_FREQUENCY_HZ = 1500.0
    private val noteNames = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    fun map(frequencyHz: Double): NoteMapping? {
        if (!frequencyHz.isFinite() || frequencyHz !in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ) return null

        val continuousMidiNote = 69.0 + 12.0 * log2(frequencyHz / 440.0)
        val midiNote = continuousMidiNote.roundToInt()
        val targetFrequencyHz = 440.0 * 2.0.pow((midiNote - 69) / 12.0)

        return NoteMapping(
            noteName = noteNames[Math.floorMod(midiNote, noteNames.size)],
            octave = Math.floorDiv(midiNote, 12) - 1,
            targetFrequencyHz = targetFrequencyHz,
            cents = 1200.0 * log2(frequencyHz / targetFrequencyHz),
        )
    }
}
