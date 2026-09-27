package com.friend.ios.phonemic.alwayson.processing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object AlwaysOnProcessingScheduler {
    const val INPUT_CHUNK_ID = "audio_chunk_id"
    private const val UNIQUE_PREFIX = "always_on_process_"

    fun onChunkRecorded(context: Context, chunkId: String, policy: ProcessingPolicy) {
        if (policy == ProcessingPolicy.MANUAL) return
        enqueue(context, chunkId, policy, replace = false)
    }

    /** Used later by a manual UI action or after Stage 3 installs the ASR processor. */
    fun enqueueNow(context: Context, chunkId: String) {
        enqueue(context, chunkId, ProcessingPolicy.REALTIME, replace = true)
    }

    /** Re-enqueue persistent jobs after installing a processor or after an app upgrade. */
    fun reschedulePending(context: Context, limit: Int = 200) {
        val appContext = context.applicationContext
        val dao = com.friend.ios.phonemic.alwayson.data.AlwaysOnDatabase.get(appContext).dao()
        val now = System.currentTimeMillis()
        dao.recoverStaleRunningJobs(now - STALE_RUNNING_MS, now)
        dao.pendingJobs(limit)
            .distinctBy { it.audioChunkId }
            .forEach { job ->
                val policy = ProcessingPolicy.fromStored(job.policy)
                if (policy != ProcessingPolicy.MANUAL) enqueue(appContext, job.audioChunkId, policy, replace = true)
            }
    }

    private fun enqueue(context: Context, chunkId: String, policy: ProcessingPolicy, replace: Boolean) {
        val constraints = Constraints.Builder().apply {
            when (policy) {
                ProcessingPolicy.REALTIME, ProcessingPolicy.MANUAL -> Unit
                ProcessingPolicy.CHARGING_ONLY -> setRequiresCharging(true)
                ProcessingPolicy.CHARGING_AND_WIFI -> {
                    setRequiresCharging(true)
                    setRequiredNetworkType(NetworkType.UNMETERED)
                }
            }
        }.build()

        val request = OneTimeWorkRequestBuilder<AlwaysOnProcessingWorker>()
            .setInputData(workDataOf(INPUT_CHUNK_ID to chunkId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_PREFIX + chunkId,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private const val STALE_RUNNING_MS = 5 * 60 * 1000L
}
