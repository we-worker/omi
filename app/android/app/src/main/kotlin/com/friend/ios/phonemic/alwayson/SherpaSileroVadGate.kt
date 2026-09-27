package com.friend.ios.phonemic.alwayson

import android.app.Application
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** sherpa-onnx-backed probability estimator. Kept behind [SpeechProbabilityEstimator]
 * so the 24H segmentation policy has no dependency on the inference runtime. */
private class SherpaSileroProbabilityEstimator(private val vad: Vad) : SpeechProbabilityEstimator {
    override fun probability(samples: FloatArray): Float = vad.compute(samples)
    override fun close() = vad.release()
}

object SherpaSileroVadGate {
    private const val TAG = "PhoneMic.SileroVad"
    private const val MODEL_ASSET = "silero_vad.onnx"

    /**
     * Create the low-power gate or return null if the model/runtime is unavailable.
     * The controller treats null as a start failure only when VAD was explicitly enabled,
     * so normal Omi stream/batch capture stays unchanged by default.
     */
    fun create(application: Application): PhoneMicVadGate? {
        return try {
            // Fail early with an actionable log instead of letting the native constructor
            // report an opaque invalid-config error.
            application.assets.open(MODEL_ASSET).use { }

            val model = SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30.0f,
            )
            val config = VadModelConfig(
                sileroVadModelConfig = model,
                sampleRate = 16_000,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            val vad = Vad(assetManager = application.assets, config = config)
            AlwaysOnSpeechGate(SherpaSileroProbabilityEstimator(vad))
        } catch (t: Throwable) {
            Log.e(TAG, "failed to initialize sherpa-onnx Silero VAD; expected asset=$MODEL_ASSET", t)
            null
        }
    }
}
