package com.friend.ios.phonemic.alwayson.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object AlwaysOnChunkState {
    const val RECORDED = "RECORDED"
}

object AlwaysOnProcessingStage {
    const val TRANSCRIBE = "TRANSCRIBE"
    const val DIARIZE = "DIARIZE"
}

object AlwaysOnSpeakerIdentity {
    const val ME = "ME"
    const val UNKNOWN = "UNKNOWN"
}

object AlwaysOnJobState {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "always_on_audio_chunks",
    indices = [Index("startedAtMs"), Index("createdAtMs")],
)
data class AudioChunkEntity(
    @PrimaryKey val id: String,
    val source: String = "PHONE_MIC",
    val startedAtMs: Long,
    val endedAtMs: Long,
    val speechDurationMs: Long,
    val audioPath: String,
    val codec: String = "opus_fs320",
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val frameSamples: Int = 320,
    val byteCount: Long,
    val opusPacketCount: Long,
    val state: String = AlwaysOnChunkState.RECORDED,
    val createdAtMs: Long,
)

@Entity(
    tableName = "always_on_processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = AudioChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioChunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("audioChunkId"),
        Index(value = ["audioChunkId", "stage"], unique = true),
        Index(value = ["state", "createdAtMs"]),
    ],
)
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
    val audioChunkId: String,
    val stage: String = AlwaysOnProcessingStage.TRANSCRIBE,
    val state: String = AlwaysOnJobState.PENDING,
    val policy: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "always_on_transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = AudioChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioChunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("audioChunkId"),
        Index(value = ["audioChunkId", "segmentIndex"], unique = true),
    ],
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val audioChunkId: String,
    val segmentIndex: Int,
    /** Milliseconds relative to the beginning of [AudioChunkEntity]. */
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val text: String,
    val language: String? = null,
    val emotion: String? = null,
    val event: String? = null,
    /** JSON arrays keep Stage 3 schema small while retaining token-level evidence. */
    val tokensJson: String = "[]",
    val tokenTimestampsMsJson: String = "[]",
    val modelId: String,
    val engineVersion: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)


@Entity(
    tableName = "always_on_speaker_segments",
    foreignKeys = [
        ForeignKey(
            entity = AudioChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioChunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("audioChunkId"),
        Index(value = ["audioChunkId", "segmentIndex"], unique = true),
        Index(value = ["audioChunkId", "clusterId"]),
    ],
)
data class SpeakerSegmentEntity(
    @PrimaryKey val id: String,
    val audioChunkId: String,
    val segmentIndex: Int,
    val clusterId: Int,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val diarizationConfidence: Float? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "always_on_speaker_clusters",
    foreignKeys = [
        ForeignKey(
            entity = AudioChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioChunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("audioChunkId"),
        Index(value = ["audioChunkId", "clusterId"], unique = true),
        Index("identity"),
    ],
)
data class SpeakerClusterEntity(
    @PrimaryKey val id: String,
    val audioChunkId: String,
    val clusterId: Int,
    val speechDurationMs: Long,
    val identity: String = AlwaysOnSpeakerIdentity.UNKNOWN,
    val identityScore: Float? = null,
    /** Little-endian float32; retained so thresholds can be re-run without decoding audio. */
    val embedding: ByteArray? = null,
    val embeddingModelId: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "always_on_me_voice_samples",
    indices = [Index("embeddingModelId"), Index("createdAtMs")],
)
data class MeVoiceSampleEntity(
    @PrimaryKey val id: String,
    val embedding: ByteArray,
    val embeddingModelId: String,
    val durationMs: Long,
    /** Provenance only: deliberately no FK so audio retention can delete old chunks safely. */
    val sourceAudioChunkId: String? = null,
    val sourceStartOffsetMs: Long? = null,
    val sourceEndOffsetMs: Long? = null,
    /** Must always be true for persisted samples; inference is never allowed to auto-enroll. */
    val userConfirmed: Boolean = true,
    val createdAtMs: Long,
)
