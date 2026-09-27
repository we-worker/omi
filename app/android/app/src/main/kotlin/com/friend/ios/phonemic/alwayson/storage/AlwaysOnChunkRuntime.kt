package com.friend.ios.phonemic.alwayson.storage

import android.content.Context
import android.util.Log
import com.friend.ios.phonemic.alwayson.data.AlwaysOnDatabase
import com.friend.ios.phonemic.alwayson.data.AudioChunkEntity
import com.friend.ios.phonemic.alwayson.data.AlwaysOnProcessingStage
import com.friend.ios.phonemic.alwayson.data.ProcessingJobEntity
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnProcessingScheduler
import com.friend.ios.phonemic.alwayson.processing.ProcessingPolicy
import java.io.File

/** Android adapter: durable file -> Room transaction -> constrained WorkManager request. */
object AlwaysOnChunkRuntime {
    private const val TAG = "PhoneMic.AlwaysOnChunk"

    fun create(context: Context): PhoneMicAlwaysOnChunkSink? = try {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, "always_on/audio")
        val dao = AlwaysOnDatabase.get(appContext).dao()

        // Close the crash window between fsync/rename and Room publication. We also
        // salvage complete Opus frames from a .part file left by process death.
        repairOrphanedAudio(directory).forEach { recovered ->
            if (dao.chunkById(recovered.id) != null) return@forEach
            val policy = ProcessingPolicy.fromPreferences(appContext)
            val endedAt = recovered.file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            val durationMs = recovered.opusPacketCount * AlwaysOnOpusChunkSink.OPUS_FRAME_MS
            val now = System.currentTimeMillis()
            runCatching {
                dao.insertRecordedChunk(
                    AudioChunkEntity(
                        id = recovered.id,
                        startedAtMs = (endedAt - durationMs).coerceAtLeast(0L),
                        endedAtMs = endedAt,
                        speechDurationMs = durationMs,
                        audioPath = recovered.file.absolutePath,
                        byteCount = recovered.byteCount,
                        opusPacketCount = recovered.opusPacketCount,
                        createdAtMs = now,
                    ),
                    processingJobs(recovered.id, policy, now),
                )
                AlwaysOnProcessingScheduler.onChunkRecorded(appContext, recovered.id, policy)
                Log.i(TAG, "recovered orphaned ${recovered.file.name}")
            }.onFailure { Log.w(TAG, "could not recover ${recovered.file.name}", it) }
        }

        AlwaysOnOpusChunkSink(
            directory = directory,
            logger = AlwaysOnChunkLogger { message, error ->
                if (error == null) Log.i(TAG, message) else Log.e(TAG, message, error)
            },
            publisher = AlwaysOnChunkPublisher { chunk ->
                val policy = ProcessingPolicy.fromPreferences(appContext)
                val now = System.currentTimeMillis()
                dao.insertRecordedChunk(
                    AudioChunkEntity(
                        id = chunk.id,
                        startedAtMs = chunk.startedAtMs,
                        endedAtMs = chunk.endedAtMs,
                        speechDurationMs = chunk.speechDurationMs,
                        audioPath = chunk.audioPath,
                        byteCount = chunk.byteCount,
                        opusPacketCount = chunk.opusPacketCount,
                        createdAtMs = now,
                    ),
                    processingJobs(chunk.id, policy, now),
                )
                AlwaysOnProcessingScheduler.onChunkRecorded(appContext, chunk.id, policy)
            },
        )
    } catch (t: Throwable) {
        Log.e(TAG, "could not create always-on chunk runtime", t)
        null
    }

    private fun processingJobs(chunkId: String, policy: ProcessingPolicy, now: Long): List<ProcessingJobEntity> = listOf(
        ProcessingJobEntity(
            id = "$chunkId:transcribe",
            audioChunkId = chunkId,
            stage = AlwaysOnProcessingStage.TRANSCRIBE,
            policy = policy.storedValue,
            createdAtMs = now,
            updatedAtMs = now,
        ),
        ProcessingJobEntity(
            id = "$chunkId:diarize",
            audioChunkId = chunkId,
            stage = AlwaysOnProcessingStage.DIARIZE,
            policy = policy.storedValue,
            createdAtMs = now + 1,
            updatedAtMs = now + 1,
        ),
    )

    private fun repairOrphanedAudio(directory: File): List<InspectedAlwaysOnChunkFile> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        directory.listFiles()
            ?.filter { it.name.endsWith(".bin.part") }
            ?.forEach { AlwaysOnChunkFileInspector.recoverPartFile(it) }

        return directory.listFiles()
            ?.mapNotNull { AlwaysOnChunkFileInspector.inspect(it) }
            ?.sortedBy { it.file.lastModified() }
            ?: emptyList()
    }
}
