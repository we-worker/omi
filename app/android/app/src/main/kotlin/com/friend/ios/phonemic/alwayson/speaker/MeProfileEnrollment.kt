package com.friend.ios.phonemic.alwayson.speaker

import android.content.Context
import com.friend.ios.phonemic.alwayson.asr.NativeOpusAlwaysOnAudioDecoder
import com.friend.ios.phonemic.alwayson.data.AlwaysOnDatabase
import com.friend.ios.phonemic.alwayson.data.MeVoiceSampleEntity
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnInferenceGate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicit user-confirmation boundary for ME enrollment. Never call this from model inference. */
object MeProfileEnrollment {
    suspend fun enrollFromChunk(
        context: Context,
        audioChunkId: String,
        startOffsetMs: Long,
        endOffsetMs: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(endOffsetMs > startOffsetMs) { "invalid enrollment range" }
            val duration = endOffsetMs - startOffsetMs
            require(duration in MIN_DURATION_MS..MAX_DURATION_MS) { "ME sample must be 1.5-20 seconds" }
            require(SpeakerModelAssets.embeddingInstalled(context)) { "speaker embedding model is not installed" }

            val dao = AlwaysOnDatabase.get(context).dao()
            val chunk = requireNotNull(dao.chunkById(audioChunkId)) { "audio chunk not found" }
            require(endOffsetMs <= chunk.speechDurationMs) { "enrollment range exceeds audio chunk" }

            AlwaysOnInferenceGate.mutex.withLock {
                val decoded = NativeOpusAlwaysOnAudioDecoder().decode(File(chunk.audioPath))
                val selected = ClusterAudioAssembler.slice(
                    decoded.samples,
                    decoded.sampleRate,
                    startOffsetMs,
                    endOffsetMs,
                )
                val embeddingEngine = SherpaSpeakerEmbeddingEngine(context)
                val embedding = try {
                    requireNotNull(embeddingEngine.embedding(selected, decoded.sampleRate)) {
                        "not enough speech for a stable speaker embedding"
                    }
                } finally {
                    embeddingEngine.close()
                }

                val id = UUID.randomUUID().toString()
                dao.insertMeVoiceSample(
                    MeVoiceSampleEntity(
                        id = id,
                        embedding = SpeakerEmbeddingCodec.encode(embedding),
                        embeddingModelId = SpeakerModelAssets.EMBEDDING_MODEL_ID,
                        durationMs = duration,
                        sourceAudioChunkId = audioChunkId,
                        sourceStartOffsetMs = startOffsetMs,
                        sourceEndOffsetMs = endOffsetMs,
                        userConfirmed = true,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
                id
            }
        }
    }

    private const val MIN_DURATION_MS = 1_500L
    private const val MAX_DURATION_MS = 20_000L
}
