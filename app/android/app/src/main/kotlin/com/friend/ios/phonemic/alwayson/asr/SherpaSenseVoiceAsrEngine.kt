package com.friend.ios.phonemic.alwayson.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * sherpa-onnx SenseVoice adapter. The recognizer is intentionally reused because
 * model construction is much more expensive than creating a per-chunk stream.
 */
class SherpaSenseVoiceAsrEngine private constructor(
    private val recognizer: OfflineRecognizer,
) : AlwaysOnAsrEngine {
    override fun transcribe(audio: DecodedAlwaysOnAudio): AlwaysOnAsrResult {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(audio.samples, sampleRate = audio.sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            AlwaysOnAsrResult(
                text = result.text.trim(),
                tokens = result.tokens.toList(),
                tokenTimestampsMs = result.timestamps.map { seconds ->
                    (seconds.coerceAtLeast(0f) * 1000f).toLong()
                },
                language = normalizeTag(result.lang),
                emotion = normalizeTag(result.emotion),
                event = normalizeTag(result.event),
                modelId = MODEL_ID,
            )
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        const val MODEL_DIR = "always_on/models/sensevoice-2024-07-17-int8"
        const val MODEL_ID = "sherpa-sensevoice-zh-en-ja-ko-yue-int8-2024-07-17"
        private const val MODEL_FILE = "$MODEL_DIR/model.int8.onnx"
        private const val TOKENS_FILE = "$MODEL_DIR/tokens.txt"

        /** Returns null until the development/production model installer has supplied both assets. */
        fun create(context: Context): SherpaSenseVoiceAsrEngine? {
            if (!assetExists(context, MODEL_FILE) || !assetExists(context, TOKENS_FILE)) return null
            val modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = MODEL_FILE,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = TOKENS_FILE,
                numThreads = 1,
                debug = false,
                provider = "cpu",
            )
            val recognizer = OfflineRecognizer(
                assetManager = context.assets,
                config = OfflineRecognizerConfig(modelConfig = modelConfig),
            )
            return SherpaSenseVoiceAsrEngine(recognizer)
        }

        private fun assetExists(context: Context, path: String): Boolean =
            try {
                context.assets.open(path).use { }
                true
            } catch (_: Throwable) {
                false
            }

        internal fun normalizeTag(value: String?): String? {
            val clean = value?.trim().orEmpty()
            if (clean.isEmpty()) return null
            return clean.removePrefix("<|").removeSuffix("|>").takeIf { it.isNotBlank() }
        }
    }
}
