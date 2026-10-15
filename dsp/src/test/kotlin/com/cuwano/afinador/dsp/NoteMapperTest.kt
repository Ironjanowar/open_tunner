package com.cuwano.afinador.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NoteMapperTest {
    @Test
    fun `maps 440 Hz to A4 at zero cents`() {
        val mapping = assertNotNull(NoteMapper.map(440.0))

        assertEquals("A", mapping.noteName)
        assertEquals(4, mapping.octave)
        assertEquals(440.0, mapping.targetFrequencyHz, 1e-9)
        assertEquals(0.0, mapping.cents, 1e-9)
    }
}
