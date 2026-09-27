package com.friend.ios.phonemic.alwayson.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.friend.ios.phonemic.alwayson.data.AlwaysOnDatabase
import com.friend.ios.phonemic.alwayson.data.AudioChunkEntity
import com.friend.ios.phonemic.alwayson.data.ProcessingJobEntity
import kotlinx.coroutines.CancellationException

sealed interface ProcessingOutcome {
    data object Completed : ProcessingOutcome
    data class Deferred(val reason: String) : ProcessingOutcome
    data class Failed(val reason: String, val retryable: Boolean) : ProcessingOutcome
}

fun interface AlwaysOnJobProcessor {
    suspend fun process(context: Context, chunk: AudioChunkEntity, job: ProcessingJobEntity): ProcessingOutcome
}

/** Process-local dispatcher installed from application startup. */
object AlwaysOnProcessorRegistry {
    @Volatile
    var processor: AlwaysOnJobProcessor? = null
}

class AlwaysOnProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val chunkId = inputData.getString(AlwaysOnProcessingScheduler.INPUT_CHUNK_ID) ?: return Result.failure()
        val processor = AlwaysOnProcessorRegistry.processor ?: return Result.success()
        val dao = AlwaysOnDatabase.get(applicationContext).dao()
        val chunk = dao.chunkById(chunkId) ?: return Result.success()

        val now = System.currentTimeMillis()
        dao.recoverStaleRunningJobs(now - RUNNING_LEASE_MS, now)
        val jobs = dao.pendingJobsForChunk(chunkId)
        if (jobs.isEmpty()) {
            return if (dao.runningJobsForChunk(chunkId) > 0) Result.retry() else Result.success()
        }

        var shouldRetry = false
        for (job in jobs) {
            val claimTime = System.currentTimeMillis()
            if (dao.claimPendingJob(job.id, claimTime) != 1) continue

            val outcome = try {
                processor.process(applicationContext, chunk, job)
            } catch (cancelled: CancellationException) {
                dao.returnJobToPending(job.id, "worker cancelled", System.currentTimeMillis())
                throw cancelled
            } catch (t: Throwable) {
                val error = "processor exception: ${t.message ?: t.javaClass.simpleName}"
                if (job.attempts + 1 >= MAX_PROCESSING_ATTEMPTS) {
                    dao.markJobFailed(job.id, error, System.currentTimeMillis())
                } else {
                    dao.returnJobToPending(job.id, error, System.currentTimeMillis())
                    shouldRetry = true
                }
                continue
            }

            when (outcome) {
                ProcessingOutcome.Completed -> dao.markJobCompleted(job.id, System.currentTimeMillis())
                is ProcessingOutcome.Deferred -> {
                    // Missing one model must not block another independent stage.
                    dao.returnJobToPending(job.id, outcome.reason, System.currentTimeMillis())
                }
                is ProcessingOutcome.Failed -> {
                    if (outcome.retryable && job.attempts + 1 < MAX_PROCESSING_ATTEMPTS) {
                        dao.returnJobToPending(job.id, outcome.reason, System.currentTimeMillis())
                        shouldRetry = true
                    } else {
                        dao.markJobFailed(job.id, outcome.reason, System.currentTimeMillis())
                    }
                }
            }
        }
        return if (shouldRetry) Result.retry() else Result.success()
    }

    companion object {
        private const val RUNNING_LEASE_MS = 5 * 60 * 1000L
        private const val MAX_PROCESSING_ATTEMPTS = 5
    }
}
