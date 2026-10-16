package com.cuwano.afinador.dsp

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NoteMapperTest {
    @Test
    fun `maps 440 Hz to A4 at zero cents`() {
        val mapping = assertNotNull(NoteMapper.map(440.0))

        assertEquals("A", mapping.noteName)
        assertEquals(4, mapping.octave)
        assertEquals(440.0, mapping.targetFrequencyHz, 1e-9)
        assertEquals(0.0, mapping.cents, 1e-9)
    }

    @Test
    fun `maps every chromatic pitch class with sharps and scientific octave`() {
        val expectedNames = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

        expectedNames.forEachIndexed { semitonesAboveC4, expectedName ->
            val midiNote = 60 + semitonesAboveC4
            val frequencyHz = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
            val mapping = assertNotNull(NoteMapper.map(frequencyHz), "MIDI note $midiNote")

            assertEquals(expectedName, mapping.noteName, "MIDI note $midiNote")
            assertEquals(4, mapping.octave, "MIDI note $midiNote")
            assertEquals(frequencyHz, mapping.targetFrequencyHz, 1e-9, "MIDI note $midiNote")
            assertEquals(0.0, mapping.cents, 1e-9, "MIDI note $midiNote")
        }
    }

    @Test
    fun `maps the same pitch class across scientific octaves`() {
        listOf(220.0 to 3, 440.0 to 4, 880.0 to 5).forEach { (frequencyHz, expectedOctave) ->
            val mapping = assertNotNull(NoteMapper.map(frequencyHz))

            assertEquals("A", mapping.noteName)
            assertEquals(expectedOctave, mapping.octave)
            assertEquals(frequencyHz, mapping.targetFrequencyHz, 1e-9)
            assertEquals(0.0, mapping.cents, 1e-9)
        }
    }

    @Test
    fun `reports signed cents relative to the nearest note`() {
        listOf(-25.0, 25.0).forEach { expectedCents ->
            val frequencyHz = 440.0 * 2.0.pow(expectedCents / 1200.0)
            val mapping = assertNotNull(NoteMapper.map(frequencyHz))

            assertEquals("A", mapping.noteName)
            assertEquals(4, mapping.octave)
            assertEquals(440.0, mapping.targetFrequencyHz, 1e-9)
            assertEquals(expectedCents, mapping.cents, 1e-9)
        }
    }

    @Test
    fun `selects the nearest note on either side of a semitone boundary`() {
        val boundaryHz = sqrt(440.0 * (440.0 * 2.0.pow(1.0 / 12.0)))

        assertEquals("A", assertNotNull(NoteMapper.map(boundaryHz * 2.0.pow(-0.001 / 1200.0))).noteName)
        assertEquals("A♯", assertNotNull(NoteMapper.map(boundaryHz * 2.0.pow(0.001 / 1200.0))).noteName)
    }

    @Test
    fun `accepts frequencies at the supported boundaries`() {
        val low = assertNotNull(NoteMapper.map(40.0))
        val high = assertNotNull(NoteMapper.map(1500.0))

        assertEquals("D♯", low.noteName)
        assertEquals(1, low.octave)
        assertEquals("F♯", high.noteName)
        assertEquals(6, high.octave)
    }

    @Test
    fun `rejects finite frequencies outside the supported range`() {
        listOf(-1.0, 0.0, 39.999, 1500.001).forEach { frequencyHz ->
            assertNull(NoteMapper.map(frequencyHz), "$frequencyHz Hz must be rejected")
        }
    }

    @Test
    fun `rejects nonfinite frequencies`() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { frequencyHz ->
            assertNull(NoteMapper.map(frequencyHz), "$frequencyHz must be rejected")
        }
    }
}
