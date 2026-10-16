package com.cuwano.afinador.tuner

import com.cuwano.afinador.dsp.TunerEngine
import com.cuwano.afinador.dsp.TunerReading

/** Processing boundary used by the capture coordinator. */
interface TunerProcessor {
    fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit)
    fun reset()
}

/** Production adapter around the platform-independent DSP engine. */
class TunerEngineProcessor(sampleRate: Int) : TunerProcessor {
    private val engine = TunerEngine(sampleRate)

    override fun accept(samples: FloatArray, count: Int, onReading: (TunerReading?) -> Unit) {
        engine.accept(samples, count, onReading)
    }

    override fun reset() {
        engine.reset()
    }
}
