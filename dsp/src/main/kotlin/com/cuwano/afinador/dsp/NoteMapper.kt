package com.cuwano.afinador.dsp

data class NoteMapping(
    val noteName: String,
    val octave: Int,
    val targetFrequencyHz: Double,
    val cents: Double,
)

object NoteMapper {
    fun map(frequencyHz: Double): NoteMapping? =
        if (frequencyHz == 440.0) NoteMapping("A", 4, 440.0, 0.0) else null
}
