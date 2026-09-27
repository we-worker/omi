package com.friend.ios.phonemic

import android.media.AudioManager
import com.friend.ios.phonemic.alwayson.PhoneMicVadGate
import com.friend.ios.phonemic.alwayson.VadSpeechEvent
import com.friend.ios.phonemic.alwayson.storage.PhoneMicAlwaysOnChunkSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicVadBatchIntegrationTest {
    private class MainLoop : PhoneMicMainLoop {
        override fun post(block: () -> Unit) = block()
        override fun postDelayed(token: Any, delayMs: Long, block: () -> Unit) = Unit
        override fun cancel(token: Any) = Unit
        override fun uptimeMillis(): Long = 0L
        override val isCurrent: Boolean = true
    }

    private class ImmediateQueue : PhoneMicTaskQueue {
        override fun execute(task: () -> Unit) = task()
    }

    private class Engine(private val onChunk: (ByteArray) -> Unit) : PhoneMicEngineHandle {
        override fun start() = Unit
        override fun teardown() = Unit
        override val audioSessionId: Int = 7
        override val lastDataUptimeMs: Long = 0L
        fun fire(bytes: ByteArray) = onChunk(bytes)
    }

    private class Encoder : PhoneMicEncoderHandle {
        val inputs = mutableListOf<ByteArray>()
        var discarded = 0
        var destroyed = 0
        override fun encode(pcm: ByteArray): List<ByteArray> {
            inputs += pcm.copyOf()
            return listOf(byteArrayOf(1, 2))
        }
        override fun discardPartial() { discarded++ }
        override fun destroy() { destroyed++ }
    }

    private class Writer : PhoneMicWriterHandle {
        var appends = 0
        var closed = 0
        override fun append(packets: List<ByteArray>, marker: String) { appends += packets.size }
        override fun closeNow(reason: String) { closed++ }
        override val sessionFramesWritten: Long get() = appends.toLong()
        override fun consumeStorageFullTransition(): Boolean = false
    }

    private class Gate : PhoneMicVadGate {
        val inputs = mutableListOf<ByteArray>()
        var resets = 0
        var closed = 0
        override fun acceptPcm16Le(pcm: ByteArray): List<VadSpeechEvent> {
            inputs += pcm.copyOf()
            return listOf(
                VadSpeechEvent.SpeechStart,
                VadSpeechEvent.SpeechPcm(byteArrayOf(42, 43)),
                VadSpeechEvent.SpeechEnd,
            )
        }
        override fun resetForGap() { resets++ }
        override fun close() { closed++ }
    }

    private class ChunkSink : PhoneMicAlwaysOnChunkSink {
        var starts = 0
        var appends = 0
        var ends = 0
        var gaps = 0
        var closes = 0
        override fun onSpeechStart() { starts++ }
        override fun appendPackets(packets: List<ByteArray>) {
            appends += packets.size
        }
        override fun onSpeechEnd(reason: String) { ends++ }
        override fun onCaptureGap() { gaps++ }
        override fun close(reason: String) { closes++ }
    }

    @Test
    fun `enabled VAD mirrors one Opus encode into legacy writer and durable chunk sink`() {
        val encoder = Encoder()
        val writer = Writer()
        val gate = Gate()
        val chunkSink = ChunkSink()
        lateinit var engine: Engine

        val ports = PhoneMicControllerPorts(
            main = MainLoop(),
            audioQueue = ImmediateQueue(),
            checkRecordAudioPermission = { true },
            audioMode = { AudioManager.MODE_NORMAL },
            activeRecordingConfigs = { emptyList() },
            setRecordingConfigListener = { },
            startForegroundService = { true },
            stopForegroundService = { },
            makeEngine = { onChunk, _ -> Engine(onChunk).also { engine = it } },
            batchDirectory = { "/tmp" },
            batchAutoMarker = { false },
            makeEncoder = { encoder },
            makeWriter = { writer },
            log = { _, _, _, _ -> },
            batchVadEnabled = { true },
            makeVadGate = { gate },
            makeAlwaysOnChunkSink = { chunkSink },
        )
        val controller = PhoneMicController.forReplay(ports)
        var startResult: Result<Unit>? = null
        controller.start(PhoneMicCaptureMode.BATCH, 123L) { startResult = it }

        assertTrue(startResult?.isSuccess == true)
        engine.fire(byteArrayOf(9, 8, 7))

        assertEquals(1, gate.inputs.size)
        assertEquals(listOf(42, 43), encoder.inputs.single().map { it.toInt() and 0xff })
        assertEquals(1, writer.appends)
        assertEquals(1, chunkSink.starts)
        assertEquals(1, chunkSink.appends)
        assertEquals(1, chunkSink.ends)
        assertTrue(encoder.discarded >= 1)

        var stopResult: Result<Unit>? = null
        controller.stop { stopResult = it }

        assertTrue(stopResult?.isSuccess == true)
        assertTrue(gate.resets >= 1)
        assertEquals(1, gate.closed)
        assertTrue(chunkSink.gaps >= 1)
        assertEquals(1, chunkSink.closes)
        assertEquals(1, writer.closed)
        assertEquals(1, encoder.destroyed)
    }
}
