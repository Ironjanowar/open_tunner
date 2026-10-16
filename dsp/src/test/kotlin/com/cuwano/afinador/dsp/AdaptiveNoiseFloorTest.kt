package com.cuwano.afinador.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveNoiseFloorTest {
    @Test
    fun `prolonged digital silence cannot pull the noise estimate below its absolute floor`() {
        val floor = newFloor(minimumNoiseRms = 0.002)

        repeat(10_000) {
            floor.observe(rms = 0.0, elapsedSeconds = 1.0, hasReliablePitch = false)
        }

        assertEquals(0.002, floor.noiseRms, absoluteTolerance = 1e-12)
        val decision = floor.evaluate(signalRms = 0.040)
        assertTrue(decision.signalToNoiseRatio.isFinite())
        assertTrue(decision.signalToNoiseRatio > 0.0)
        assertTrue(decision.isOpen)
    }

    @Test
    fun `clipped frames are never learned as ambient noise`() {
        val floor = newFloor()

        floor.observe(
            rms = 0.95,
            elapsedSeconds = 5.0,
            hasReliablePitch = false,
            isClipped = true,
        )

        assertEquals(0.010, floor.noiseRms, absoluteTolerance = 0.0)
    }

    @Test
    fun `clipped frame cannot produce an ordinary open gate decision`() {
        val floor = newFloor()

        val decision = floor.observe(
            rms = 0.95,
            elapsedSeconds = 0.02,
            hasReliablePitch = false,
            isClipped = true,
        )
        val explicitlyDegraded = decision.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && it.name in setOf("isDegraded", "getDegraded") }
            ?.invoke(decision) == true

        assertTrue(
            decision.isOpen.not() || explicitlyDegraded,
            "clipped input must close the gate or mark its decision as degraded",
        )
    }

    @Test
    fun `a note already sounding at startup is not learned as ambient noise`() {
        val floor = newFloor()

        repeat(50) {
            floor.observe(rms = 0.080, elapsedSeconds = 0.02, hasReliablePitch = false)
        }

        assertEquals(0.010, floor.noiseRms, absoluteTolerance = 0.0)
        assertTrue(floor.evaluate(signalRms = 0.080).isOpen)
    }

    @Test
    fun `noise floor moves halfway toward an observation after its five second half life`() {
        val floor = AdaptiveNoiseFloor(
            initialNoiseRms = 0.010,
            minimumNoiseRms = 0.001,
            halfLifeSeconds = 5.0,
            minimumSignalToNoiseRatio = 3.0,
        )

        floor.observe(rms = 0.030, elapsedSeconds = 5.0, hasReliablePitch = false)

        assertEquals(0.020, floor.noiseRms, absoluteTolerance = 1e-12)
    }

    @Test
    fun `half life depends on elapsed seconds rather than update count`() {
        val oneUpdate = newFloor()
        val fiveUpdates = newFloor()

        oneUpdate.observe(rms = 0.030, elapsedSeconds = 5.0, hasReliablePitch = false)
        repeat(5) {
            fiveUpdates.observe(rms = 0.030, elapsedSeconds = 1.0, hasReliablePitch = false)
        }

        assertEquals(oneUpdate.noiseRms, fiveUpdates.noiseRms, absoluteTolerance = 1e-12)
        assertEquals(0.020, fiveUpdates.noiseRms, absoluteTolerance = 1e-12)
    }

    @Test
    fun `reliable pitched signal is gated without being absorbed into the floor`() {
        val floor = newFloor()

        val decision = floor.observe(rms = 0.080, elapsedSeconds = 5.0, hasReliablePitch = true)

        assertEquals(0.010, floor.noiseRms, absoluteTolerance = 0.0)
        assertEquals(8.0, decision.signalToNoiseRatio, absoluteTolerance = 1e-12)
        assertTrue(decision.isOpen)
    }

    @Test
    fun `signal to noise gate opens at the configured ratio and rejects weaker levels`() {
        val floor = newFloor()

        val below = floor.evaluate(signalRms = 0.029)
        val boundary = floor.evaluate(signalRms = 0.030)

        assertEquals(2.9, below.signalToNoiseRatio, absoluteTolerance = 1e-12)
        assertFalse(below.isOpen)
        assertEquals(3.0, boundary.signalToNoiseRatio, absoluteTolerance = 1e-12)
        assertTrue(boundary.isOpen)
    }

    @Test
    fun `reset restores the deterministic startup floor and closes the prior signal`() {
        val floor = newFloor()
        floor.observe(rms = 0.030, elapsedSeconds = 5.0, hasReliablePitch = false)
        assertTrue(floor.evaluate(signalRms = 0.040).isOpen.not())

        floor.reset()

        assertEquals(0.010, floor.noiseRms, absoluteTolerance = 0.0)
        assertTrue(floor.evaluate(signalRms = 0.040).isOpen)
    }

    private fun newFloor(minimumNoiseRms: Double = 0.001) = AdaptiveNoiseFloor(
        initialNoiseRms = 0.010,
        minimumNoiseRms = minimumNoiseRms,
        halfLifeSeconds = 5.0,
        minimumSignalToNoiseRatio = 3.0,
    )
}
