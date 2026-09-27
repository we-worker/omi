package com.friend.ios.phonemic.alwayson.processing

import android.content.Context
import com.friend.ios.phonemic.alwayson.asr.SherpaTranscribeJobProcessor
import com.friend.ios.phonemic.alwayson.data.AlwaysOnProcessingStage
import com.friend.ios.phonemic.alwayson.data.AudioChunkEntity
import com.friend.ios.phonemic.alwayson.data.ProcessingJobEntity
import com.friend.ios.phonemic.alwayson.speaker.SherpaDiarizeJobProcessor

class AlwaysOnPipelineJobProcessor(
    private val transcribe: AlwaysOnJobProcessor = SherpaTranscribeJobProcessor(),
    private val diarize: AlwaysOnJobProcessor = SherpaDiarizeJobProcessor(),
) : AlwaysOnJobProcessor {
    override suspend fun process(context: Context, chunk: AudioChunkEntity, job: ProcessingJobEntity): ProcessingOutcome =
        when (job.stage) {
            AlwaysOnProcessingStage.TRANSCRIBE -> transcribe.process(context, chunk, job)
            AlwaysOnProcessingStage.DIARIZE -> diarize.process(context, chunk, job)
            else -> ProcessingOutcome.Failed("unknown processing stage: ${job.stage}", retryable = false)
        }
}
