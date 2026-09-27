package com.friend.ios.phonemic.alwayson.asr

import android.content.Context
import com.friend.ios.phonemic.alwayson.data.AlwaysOnDatabase
import com.friend.ios.phonemic.alwayson.data.AlwaysOnProcessingStage
import com.friend.ios.phonemic.alwayson.data.AudioChunkEntity
import com.friend.ios.phonemic.alwayson.data.ProcessingJobEntity
import com.friend.ios.phonemic.alwayson.data.TranscriptSegmentEntity
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnJobProcessor
import com.friend.ios.phonemic.alwayson.processing.ProcessingOutcome
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnInferenceGate
import java.io.File
import kotlinx.coroutines.sync.withLock

/** WorkManager processor for TRANSCRIBE. Model inference is serialized to cap RAM usage. */
class SherpaTranscribeJobProcessor(
    private val decoder: AlwaysOnAudioDecoder = NativeOpusAlwaysOnAudioDecoder(),
) : AlwaysOnJobProcessor {
    @Volatile private var engine: AlwaysOnAsrEngine? = null

    override suspend fun process(
        context: Context,
        chunk: AudioChunkEntity,
        job: ProcessingJobEntity,
    ): ProcessingOutcome {
        if (job.stage != AlwaysOnProcessingStage.TRANSCRIBE) {
            return ProcessingOutcome.Failed("unsupported processing stage: ${job.stage}", retryable = false)
        }
        val file = File(chunk.audioPath)
        if (!file.isFile) return ProcessingOutcome.Failed("audio chunk is missing", retryable = false)

        return AlwaysOnInferenceGate.mutex.withLock {
            val currentEngine = engine ?: SherpaSenseVoiceAsrEngine.create(context)?.also { engine = it }
                ?: return@withLock ProcessingOutcome.Deferred("SenseVoice model is not installed")

            val audio = try {
                decoder.decode(file)
            } catch (t: Throwable) {
                return@withLock ProcessingOutcome.Failed(
                    "Opus decode failed: ${t.message ?: t.javaClass.simpleName}",
                    retryable = false,
                )
            }
            if (audio.samples.isEmpty()) {
                return@withLock ProcessingOutcome.Failed("decoded audio is empty", retryable = false)
            }
            if (chunk.opusPacketCount > 0 && audio.decodedPacketCount != chunk.opusPacketCount) {
                return@withLock ProcessingOutcome.Failed(
                    "packet count mismatch: expected ${chunk.opusPacketCount}, decoded ${audio.decodedPacketCount}",
                    retryable = false,
                )
            }

            val result = try {
                currentEngine.transcribe(audio)
            } catch (t: Throwable) {
                return@withLock ProcessingOutcome.Failed(
                    "sherpa ASR failed: ${t.message ?: t.javaClass.simpleName}",
                    retryable = true,
                )
            }

            val timestamps = TranscriptSerialization.clampTimestamps(
                result.tokenTimestampsMs,
                chunk.speechDurationMs,
            )
            val now = System.currentTimeMillis()
            AlwaysOnDatabase.get(context).dao().upsertTranscript(
                TranscriptSegmentEntity(
                    id = "${chunk.id}:asr:0",
                    audioChunkId = chunk.id,
                    segmentIndex = 0,
                    startOffsetMs = 0L,
                    endOffsetMs = chunk.speechDurationMs,
                    text = result.text,
                    language = result.language,
                    emotion = result.emotion,
                    event = result.event,
                    tokensJson = TranscriptSerialization.strings(result.tokens),
                    tokenTimestampsMsJson = TranscriptSerialization.longs(timestamps),
                    modelId = result.modelId,
                    engineVersion = "sherpa-onnx-1.13.8",
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            )
            ProcessingOutcome.Completed
        }
    }
}
