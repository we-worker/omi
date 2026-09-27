package com.friend.ios.phonemic.alwayson.storage

import com.friend.ios.batch.BatchFrameEncoder
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Controller-facing sink. Every call is confined to PhoneMicAudio. */
interface PhoneMicAlwaysOnChunkSink {
    fun onSpeechStart()
    fun appendPackets(packets: List<ByteArray>)
    fun onSpeechEnd(reason: String = "vad")
    fun onCaptureGap()
    fun close(reason: String = "stop")
}

data class RecordedAlwaysOnChunk(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val speechDurationMs: Long,
    val audioPath: String,
    val byteCount: Long,
    val opusPacketCount: Long,
)

fun interface AlwaysOnChunkPublisher {
    fun publish(chunk: RecordedAlwaysOnChunk)
}

fun interface AlwaysOnChunkLogger {
    fun log(message: String, error: Throwable?)
}

/**
 * Pure Kotlin file sink: one file per VAD speech segment using Omi's existing
 * length-prefixed Opus packet layout. Files are written as .part, fsynced, then
 * atomically renamed before metadata is published.
 */
class AlwaysOnOpusChunkSink internal constructor(
    private val directory: File,
    private val publisher: AlwaysOnChunkPublisher,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val logger: AlwaysOnChunkLogger = AlwaysOnChunkLogger { _, _ -> },
) : PhoneMicAlwaysOnChunkSink {
    private val frameEncoder = BatchFrameEncoder()

    private var chunkId: String? = null
    private var partFile: File? = null
    private var out: RandomAccessFile? = null
    private var committedBytes = 0L
    private var committedPackets = 0L

    override fun onSpeechStart() {
        if (chunkId != null) finalizeChunk("implicit_restart")
        chunkId = UUID.randomUUID().toString()
        committedBytes = 0L
        committedPackets = 0L
    }

    override fun appendPackets(packets: List<ByteArray>) {
        if (chunkId == null) onSpeechStart()
        if (packets.isEmpty()) return

        val output = ensureOpen() ?: return
        try {
            packets.forEach { packet ->
                committedBytes += frameEncoder.write(output, packet, committedBytes)
                committedPackets += 1
            }
        } catch (t: Throwable) {
            logger.log("always-on chunk write failed", t)
            abortCurrent()
        }
    }

    override fun onSpeechEnd(reason: String) {
        finalizeChunk(reason)
    }

    override fun onCaptureGap() {
        finalizeChunk("capture_gap")
    }

    override fun close(reason: String) {
        finalizeChunk(reason)
    }

    private fun ensureOpen(): RandomAccessFile? {
        out?.let { return it }
        val id = chunkId ?: return null
        return try {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                logger.log("could not create ${directory.absolutePath}", null)
                abortCurrent()
                return null
            }
            val file = File(directory, "audio_alwayson_${id}.bin.part")
            RandomAccessFile(file, "rw").also {
                partFile = file
                out = it
            }
        } catch (t: Throwable) {
            logger.log("always-on chunk open failed", t)
            abortCurrent()
            null
        }
    }

    private fun finalizeChunk(reason: String) {
        val id = chunkId ?: return
        val currentOut = out
        val currentPart = partFile
        var durable = true

        if (currentOut != null) {
            try {
                currentOut.fd.sync()
            } catch (t: Throwable) {
                durable = false
                logger.log("fsync failed for $id", t)
            }
            runCatching { currentOut.close() }
        }

        if (currentPart != null && committedPackets > 0L && durable) {
            val finalFile = File(currentPart.parentFile, currentPart.name.removeSuffix(".part"))
            if (currentPart.renameTo(finalFile)) {
                // Every persisted packet is one 20ms / 320-sample Opus frame. Deriving
                // duration from committed packets (instead of incoming VAD PCM bytes)
                // keeps metadata aligned with what is actually decodable on disk even
                // when the Opus encoder drops a frame or discards a sub-frame tail.
                val durationMs = committedPackets * OPUS_FRAME_MS
                val endedAt = nowMs()
                val startedAt = (endedAt - durationMs).coerceAtLeast(0L)
                try {
                    publisher.publish(
                        RecordedAlwaysOnChunk(
                            id = id,
                            startedAtMs = startedAt,
                            endedAtMs = endedAt,
                            speechDurationMs = durationMs,
                            audioPath = finalFile.absolutePath,
                            byteCount = finalFile.length(),
                            opusPacketCount = committedPackets,
                        ),
                    )
                    logger.log("finalized ${finalFile.name} (${durationMs}ms, reason=$reason)", null)
                } catch (t: Throwable) {
                    // Keep durable audio even if metadata publication fails. The repair
                    // scanner reconstructs the Room row/job from the finalized file on
                    // the next runtime initialization.
                    logger.log("metadata publish failed for ${finalFile.name}", t)
                }
            } else {
                logger.log("rename failed for ${currentPart.name}; leaving .part for repair", null)
            }
        } else if (currentPart != null && committedPackets == 0L) {
            currentPart.delete()
        }

        resetState()
    }

    private fun abortCurrent() {
        runCatching { out?.close() }
        partFile?.delete()
        resetState()
    }

    private fun resetState() {
        out = null
        partFile = null
        chunkId = null
        committedBytes = 0L
        committedPackets = 0L
    }

    companion object {
        const val OPUS_FRAME_MS = 20L
    }
}
