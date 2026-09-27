package com.friend.ios.phonemic.alwayson.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
abstract class AlwaysOnDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertChunk(chunk: AudioChunkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertJob(job: ProcessingJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertJobs(jobs: List<ProcessingJobEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertTranscript(segment: TranscriptSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertSpeakerSegments(segments: List<SpeakerSegmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertSpeakerClusters(clusters: List<SpeakerClusterEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertMeVoiceSample(sample: MeVoiceSampleEntity)

    @Transaction
    open fun insertRecordedChunk(chunk: AudioChunkEntity, jobs: List<ProcessingJobEntity>) {
        insertChunk(chunk)
        insertJobs(jobs)
    }

    @Query("SELECT * FROM always_on_audio_chunks WHERE id = :id LIMIT 1")
    abstract fun chunkById(id: String): AudioChunkEntity?

    @Query("SELECT * FROM always_on_processing_jobs WHERE audioChunkId = :chunkId AND state = 'PENDING' ORDER BY CASE stage WHEN 'TRANSCRIBE' THEN 0 WHEN 'DIARIZE' THEN 1 ELSE 2 END, createdAtMs ASC")
    abstract fun pendingJobsForChunk(chunkId: String): List<ProcessingJobEntity>

    @Query("SELECT COUNT(*) FROM always_on_processing_jobs WHERE audioChunkId = :chunkId AND state = 'RUNNING'")
    abstract fun runningJobsForChunk(chunkId: String): Int

    @Query("SELECT * FROM always_on_processing_jobs WHERE state = 'PENDING' ORDER BY createdAtMs ASC LIMIT :limit")
    abstract fun pendingJobs(limit: Int): List<ProcessingJobEntity>

    @Query("SELECT * FROM always_on_transcript_segments WHERE audioChunkId = :chunkId ORDER BY segmentIndex ASC")
    abstract fun transcriptSegmentsForChunk(chunkId: String): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM always_on_speaker_segments WHERE audioChunkId = :chunkId ORDER BY segmentIndex ASC")
    abstract fun speakerSegmentsForChunk(chunkId: String): List<SpeakerSegmentEntity>

    @Query("SELECT * FROM always_on_speaker_clusters WHERE audioChunkId = :chunkId ORDER BY clusterId ASC")
    abstract fun speakerClustersForChunk(chunkId: String): List<SpeakerClusterEntity>

    @Query("SELECT * FROM always_on_me_voice_samples WHERE userConfirmed = 1 AND embeddingModelId = :modelId ORDER BY createdAtMs ASC")
    abstract fun confirmedMeVoiceSamples(modelId: String): List<MeVoiceSampleEntity>

    @Query("DELETE FROM always_on_speaker_segments WHERE audioChunkId = :chunkId")
    abstract fun deleteSpeakerSegmentsForChunk(chunkId: String)

    @Query("DELETE FROM always_on_speaker_clusters WHERE audioChunkId = :chunkId")
    abstract fun deleteSpeakerClustersForChunk(chunkId: String)

    @Transaction
    open fun replaceDiarization(
        chunkId: String,
        clusters: List<SpeakerClusterEntity>,
        segments: List<SpeakerSegmentEntity>,
    ) {
        deleteSpeakerSegmentsForChunk(chunkId)
        deleteSpeakerClustersForChunk(chunkId)
        if (clusters.isNotEmpty()) upsertSpeakerClusters(clusters)
        if (segments.isNotEmpty()) upsertSpeakerSegments(segments)
    }

    @Query("UPDATE always_on_processing_jobs SET state = 'RUNNING', attempts = attempts + 1, lastError = NULL, updatedAtMs = :nowMs WHERE id = :id AND state = 'PENDING'")
    abstract fun claimPendingJob(id: String, nowMs: Long): Int

    @Query("UPDATE always_on_processing_jobs SET state = 'PENDING', lastError = :error, updatedAtMs = :nowMs WHERE id = :id")
    abstract fun returnJobToPending(id: String, error: String?, nowMs: Long)

    @Query("UPDATE always_on_processing_jobs SET state = 'PENDING', lastError = 'worker lease expired', updatedAtMs = :nowMs WHERE state = 'RUNNING' AND updatedAtMs <= :olderThanMs")
    abstract fun recoverStaleRunningJobs(olderThanMs: Long, nowMs: Long): Int

    @Query("UPDATE always_on_processing_jobs SET state = 'COMPLETED', lastError = NULL, updatedAtMs = :nowMs WHERE id = :id")
    abstract fun markJobCompleted(id: String, nowMs: Long)

    @Query("UPDATE always_on_processing_jobs SET state = 'FAILED', lastError = :error, updatedAtMs = :nowMs WHERE id = :id")
    abstract fun markJobFailed(id: String, error: String?, nowMs: Long)
}

@Database(
    entities = [
        AudioChunkEntity::class,
        ProcessingJobEntity::class,
        TranscriptSegmentEntity::class,
        SpeakerSegmentEntity::class,
        SpeakerClusterEntity::class,
        MeVoiceSampleEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AlwaysOnDatabase : RoomDatabase() {
    abstract fun dao(): AlwaysOnDao

    companion object {
        @Volatile private var instance: AlwaysOnDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE always_on_audio_chunks ADD COLUMN opusPacketCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE always_on_audio_chunks SET opusPacketCount = speechDurationMs / 20 WHERE opusPacketCount = 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `always_on_transcript_segments` (
                        `id` TEXT NOT NULL,
                        `audioChunkId` TEXT NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `startOffsetMs` INTEGER NOT NULL,
                        `endOffsetMs` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `language` TEXT,
                        `emotion` TEXT,
                        `event` TEXT,
                        `tokensJson` TEXT NOT NULL,
                        `tokenTimestampsMsJson` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `engineVersion` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`audioChunkId`) REFERENCES `always_on_audio_chunks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_always_on_transcript_segments_audioChunkId` ON `always_on_transcript_segments` (`audioChunkId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_always_on_transcript_segments_audioChunkId_segmentIndex` ON `always_on_transcript_segments` (`audioChunkId`, `segmentIndex`)")
            }
        }


        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `always_on_speaker_segments` (
                        `id` TEXT NOT NULL,
                        `audioChunkId` TEXT NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `clusterId` INTEGER NOT NULL,
                        `startOffsetMs` INTEGER NOT NULL,
                        `endOffsetMs` INTEGER NOT NULL,
                        `diarizationConfidence` REAL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`audioChunkId`) REFERENCES `always_on_audio_chunks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_always_on_speaker_segments_audioChunkId` ON `always_on_speaker_segments` (`audioChunkId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_always_on_speaker_segments_audioChunkId_segmentIndex` ON `always_on_speaker_segments` (`audioChunkId`, `segmentIndex`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_always_on_speaker_segments_audioChunkId_clusterId` ON `always_on_speaker_segments` (`audioChunkId`, `clusterId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `always_on_speaker_clusters` (
                        `id` TEXT NOT NULL,
                        `audioChunkId` TEXT NOT NULL,
                        `clusterId` INTEGER NOT NULL,
                        `speechDurationMs` INTEGER NOT NULL,
                        `identity` TEXT NOT NULL,
                        `identityScore` REAL,
                        `embedding` BLOB,
                        `embeddingModelId` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`audioChunkId`) REFERENCES `always_on_audio_chunks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_always_on_speaker_clusters_audioChunkId` ON `always_on_speaker_clusters` (`audioChunkId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_always_on_speaker_clusters_audioChunkId_clusterId` ON `always_on_speaker_clusters` (`audioChunkId`, `clusterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_always_on_speaker_clusters_identity` ON `always_on_speaker_clusters` (`identity`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `always_on_me_voice_samples` (
                        `id` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL,
                        `embeddingModelId` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sourceAudioChunkId` TEXT,
                        `sourceStartOffsetMs` INTEGER,
                        `sourceEndOffsetMs` INTEGER,
                        `userConfirmed` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_always_on_me_voice_samples_embeddingModelId` ON `always_on_me_voice_samples` (`embeddingModelId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_always_on_me_voice_samples_createdAtMs` ON `always_on_me_voice_samples` (`createdAtMs`)")

                // Existing Stage 3 chunks get a diarization job without disturbing ASR state.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO always_on_processing_jobs
                        (id, audioChunkId, stage, state, policy, attempts, lastError, createdAtMs, updatedAtMs)
                    SELECT audioChunkId || ':diarize', audioChunkId, 'DIARIZE', 'PENDING',
                           MIN(policy), 0, NULL, MIN(createdAtMs) + 1, MIN(createdAtMs) + 1
                    FROM always_on_processing_jobs
                    GROUP BY audioChunkId
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): AlwaysOnDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlwaysOnDatabase::class.java,
                    "always_on.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
