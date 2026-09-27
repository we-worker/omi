package com.friend.ios.phonemic.alwayson.asr

import android.content.Context
import android.util.Log
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnProcessingScheduler
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnProcessorRegistry
import com.friend.ios.phonemic.alwayson.processing.AlwaysOnPipelineJobProcessor
import java.util.concurrent.Executors

/** Installs the lazy local ASR + speaker pipeline before WorkManager creates workers. */
object AlwaysOnAsrRuntime {
    private const val TAG = "AlwaysOn.AsrRuntime"
    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AlwaysOnAsrStartup").apply { isDaemon = true }
    }

    fun install(context: Context) {
        val appContext = context.applicationContext
        AlwaysOnProcessorRegistry.processor = AlwaysOnPipelineJobProcessor()
        startupExecutor.execute {
            runCatching { AlwaysOnProcessingScheduler.reschedulePending(appContext) }
                .onFailure { Log.w(TAG, "could not reschedule pending always-on jobs", it) }
        }
    }
}
