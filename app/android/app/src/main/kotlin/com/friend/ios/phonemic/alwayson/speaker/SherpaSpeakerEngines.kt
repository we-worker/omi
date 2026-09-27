package com.friend.ios.phonemic.alwayson.speaker

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

object SpeakerModelAssets {
    const val SEGMENTATION_MODEL = "always_on/models/pyannote-segmentation-3.0/model.onnx"
    const val EMBEDDING_MODEL = "always_on/models/speaker-eres2net-zh/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    const val EMBEDDING_MODEL_ID = "3dspeaker-eres2net-base-zh-16k"

    fun installed(context: Context): Boolean = assetExists(context, SEGMENTATION_MODEL) && assetExists(context, EMBEDDING_MODEL)
    fun embeddingInstalled(context: Context): Boolean = assetExists(context, EMBEDDING_MODEL)

    private fun assetExists(context: Context, path: String): Boolean = runCatching {
        context.assets.open(path).use { }
        true
    }.getOrDefault(false)
}

/** Creates/releases the diarizer per job so its internal embedding model does not remain duplicated in RAM. */
class SherpaOfflineDiarizationEngine(private val context: Context) {
    fun diarize(samples: FloatArray, sampleRate: Int): List<DiarizedRegion> {
        require(sampleRate == 16_000) { "speaker diarization requires 16 kHz PCM" }
        val diarizer = OfflineSpeakerDiarization(
            assetManager = context.assets,
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SpeakerModelAssets.SEGMENTATION_MODEL),
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = SpeakerModelAssets.EMBEDDING_MODEL,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.30f,
                minDurationOff = 0.50f,
            ),
        )
        return try {
            diarizer.process(samples).map { segment ->
                DiarizedRegion(
                    startOffsetMs = (segment.start * 1000f).toLong().coerceAtLeast(0L),
                    endOffsetMs = (segment.end * 1000f).toLong().coerceAtLeast(0L),
                    clusterId = segment.speaker,
                    // Keep nullable until the Kotlin AAR confidence accessor is relied on everywhere.
                    confidence = null,
                )
            }
        } finally {
            diarizer.release()
        }
    }
}

class SherpaSpeakerEmbeddingEngine(private val context: Context) : AutoCloseable {
    private var extractor: SpeakerEmbeddingExtractor? = null

    private fun extractor(): SpeakerEmbeddingExtractor = extractor ?: SpeakerEmbeddingExtractor(
        assetManager = context.assets,
        config = SpeakerEmbeddingExtractorConfig(
            model = SpeakerModelAssets.EMBEDDING_MODEL,
            numThreads = 1,
            debug = false,
            provider = "cpu",
        ),
    ).also { extractor = it }

    fun embedding(samples: FloatArray, sampleRate: Int): FloatArray? {
        if (sampleRate != 16_000 || samples.size < MIN_SAMPLES) return null
        val current = extractor()
        val stream = current.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            if (!current.isReady(stream)) null else current.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        extractor?.release()
        extractor = null
    }

    companion object {
        private const val MIN_SAMPLES = 16_000 // ~1 second; prefer Unknown over weak embeddings.
    }
}
