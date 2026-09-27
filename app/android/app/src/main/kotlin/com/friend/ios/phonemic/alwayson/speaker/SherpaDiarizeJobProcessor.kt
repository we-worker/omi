package com.friend.ios.phonemic.alwayson.speaker

import android.content.Context
import com.friend.ios.phonemic.alwayson.asr.AlwaysOnAudioDecoder
import com.friend.ios.phonemic.alwayson.asr.NativeOpusAlwaysOnAudioDecoder
import com.friend.ios.phonemic.alwayson.data.AlwaysOnDatabase
import com.friend.ios.phonemic.alwayson.data.AlwaysOnProcessingStage
import com.friend.ios.phonemic.alwayson.data.AudioChunkEntity
import com.friend.ios.phonemic.alwayson.data.ProcessingJobEntity
import com.friend.ios.phonemic.alwayson.data.SpeakerClusterEntity
import com.friend.ios.phonemic.alwayson.data.SpeakerSegmentEntity
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnInferenceGate
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnJobProcessor
import com.friend.ios.phonemic.alwayson.processing.ProcessingOutcome
import java.io.File
import kotlinx.coroutines.sync.withLock

class SherpaDiarizeJobProcessor(
    private val decoder: AlwaysOnAudioDecoder = NativeOpusAlwaysOnAudioDecoder(),
    private val matcher: MeProfileMatcher = MeProfileMatcher(),
) : AlwaysOnJobProcessor {
    override suspend fun process(context: Context, chunk: AudioChunkEntity, job: ProcessingJobEntity): ProcessingOutcome {
        if (job.stage != AlwaysOnProcessingStage.DIARIZE) {
            return ProcessingOutcome.Failed("unsupported processing stage: ${job.stage}", retryable = false)
        }
        if (!SpeakerModelAssets.installed(context)) {
            return ProcessingOutcome.Deferred("speaker diarization models are not installed")
        }
        val file = File(chunk.audioPath)
        if (!file.isFile) return ProcessingOutcome.Failed("audio chunk is missing", retryable = false)

        return AlwaysOnInferenceGate.mutex.withLock {
            val audio = try {
                decoder.decode(file)
            } catch (t: Throwable) {
                return@withLock ProcessingOutcome.Failed("Opus decode failed: ${t.message ?: t.javaClass.simpleName}", false)
            }
            if (audio.samples.isEmpty()) return@withLock ProcessingOutcome.Failed("decoded audio is empty", false)

            val regions = try {
                SherpaOfflineDiarizationEngine(context).diarize(audio.samples, audio.sampleRate)
            } catch (t: Throwable) {
                return@withLock ProcessingOutcome.Failed("sherpa diarization failed: ${t.message ?: t.javaClass.simpleName}", true)
            }
            if (regions.isEmpty()) {
                AlwaysOnDatabase.get(context).dao().replaceDiarization(chunk.id, emptyList(), emptyList())
                return@withLock ProcessingOutcome.Completed
            }

            val embeddingEngine = SherpaSpeakerEmbeddingEngine(context)
            val dao = AlwaysOnDatabase.get(context).dao()
            val meSamples = dao.confirmedMeVoiceSamples(SpeakerModelAssets.EMBEDDING_MODEL_ID)
                .mapNotNull { runCatching { SpeakerEmbeddingCodec.decode(it.embedding) }.getOrNull() }
            val now = System.currentTimeMillis()

            val clusters = try {
                regions.groupBy { it.clusterId }.toSortedMap().map { (clusterId, clusterRegions) ->
                    val clusterAudio = ClusterAudioAssembler.assemble(audio.samples, audio.sampleRate, clusterRegions)
                    val embedding = embeddingEngine.embedding(clusterAudio, audio.sampleRate)
                    val match = if (embedding != null) matcher.match(embedding, meSamples) else null
                    SpeakerClusterEntity(
                    id = "${chunk.id}:speaker:$clusterId",
                    audioChunkId = chunk.id,
                    clusterId = clusterId,
                    speechDurationMs = clusterRegions.sumOf { (it.endOffsetMs - it.startOffsetMs).coerceAtLeast(0L) },
                    identity = match?.identity ?: com.friend.ios.phonemic.alwayson.data.AlwaysOnSpeakerIdentity.UNKNOWN,
                    identityScore = match?.score,
                    embedding = embedding?.let(SpeakerEmbeddingCodec::encode),
                    embeddingModelId = embedding?.let { SpeakerModelAssets.EMBEDDING_MODEL_ID },
                    createdAtMs = now,
                        updatedAtMs = now,
                    )
                }
            } finally {
                embeddingEngine.close()
            }
            val segments = regions.sortedWith(compareBy<DiarizedRegion> { it.startOffsetMs }.thenBy { it.endOffsetMs })
                .mapIndexed { index, region ->
                    SpeakerSegmentEntity(
                        id = "${chunk.id}:speaker-segment:$index",
                        audioChunkId = chunk.id,
                        segmentIndex = index,
                        clusterId = region.clusterId,
                        startOffsetMs = region.startOffsetMs.coerceAtMost(chunk.speechDurationMs),
                        endOffsetMs = region.endOffsetMs.coerceAtMost(chunk.speechDurationMs),
                        diarizationConfidence = region.confidence,
                        createdAtMs = now,
                        updatedAtMs = now,
                    )
                }
            dao.replaceDiarization(chunk.id, clusters, segments)
            ProcessingOutcome.Completed
        }
    }
}
