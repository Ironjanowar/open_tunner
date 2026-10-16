package com.cuwano.afinador.tuner

import com.cuwano.afinador.dsp.SensitivityProfile
import com.cuwano.afinador.dsp.TunerEngine
import com.cuwano.afinador.dsp.TunerReading

/** Processing boundary used by the capture coordinator. */
interface TunerProcessor {
    fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit)
    fun reset()

    /** Applies the new sensitivity to every following frame without rebuilding the chain. */
    fun setProfile(profile: SensitivityProfile)
}

/** Production adapter around the platform-independent DSP engine. */
class TunerEngineProcessor(
    sampleRate: Int,
    profile: SensitivityProfile = SensitivityProfile.DEFAULT,
) : TunerProcessor {
    private val engine = TunerEngine(sampleRate, profile = profile)

    override fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit) {
        engine.accept(samples, count, onReading)
    }

    override fun reset() {
        engine.reset()
    }

    override fun setProfile(profile: SensitivityProfile) {
        engine.setProfile(profile)
    }
}
