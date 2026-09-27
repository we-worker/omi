package com.friend.ios.phonemic.alwayson

import kotlin.math.ceil

/** Probability-only VAD surface. Production uses sherpa-onnx Silero; tests use a fake. */
interface SpeechProbabilityEstimator {
    fun probability(samples: FloatArray): Float
    fun close() {}
}

/**
 * Ordered VAD events emitted on Omi's single PhoneMicAudio queue.
 *
 * A single AudioRecord chunk can contain the end of one speech segment and the start of
 * another, so explicit ordered events are safer than returning only a list of kept PCM.
 */
sealed interface VadSpeechEvent {
    data object SpeechStart : VadSpeechEvent
    data class SpeechPcm(val pcm16Le: ByteArray) : VadSpeechEvent
    data object SpeechEnd : VadSpeechEvent
}

interface PhoneMicVadGate {
    fun acceptPcm16Le(pcm: ByteArray): List<VadSpeechEvent>
    fun resetForGap()
    fun close()
}

data class SpeechGateConfig(
    val sampleRate: Int = 16_000,
    val windowSamples: Int = 512,
    val preRollMs: Int = 800,
    val startThreshold: Float = 0.55f,
    val endThreshold: Float = 0.35f,
    val endSilenceMs: Int = 550,
    /** Hard segment bound for pathological continuous speech/noise. */
    val maxSpeechMs: Int = 60_000,
) {
    init {
        require(sampleRate > 0)
        require(windowSamples > 0)
        require(preRollMs >= 0)
        require(startThreshold in 0f..1f)
        require(endThreshold in 0f..1f)
        require(endThreshold <= startThreshold)
        require(endSilenceMs > 0)
        require(maxSpeechMs > 0)
    }
}

/**
 * Lightweight always-on speech gate designed for 24H capture.
 *
 * Only fixed Silero windows are scored while recording; ASR/diarization never run here.
 * The gate retains pre-roll, uses hysteresis, keeps a short trailing tail and emits hard
 * segment boundaries so downstream storage never has to infer when speech ended.
 */
class AlwaysOnSpeechGate(
    private val estimator: SpeechProbabilityEstimator,
    private val config: SpeechGateConfig = SpeechGateConfig(),
) : PhoneMicVadGate {
    private val frameBytes = config.windowSamples * 2
    private val frameBuffer = ByteArray(frameBytes)
    private var frameFill = 0
    private val floatFrame = FloatArray(config.windowSamples)

    private val preRollFrames = ArrayDeque<ByteArray>()
    private val preRollCapacity = framesForMs(config.preRollMs)
    private val endSilenceFrames = framesForMs(config.endSilenceMs).coerceAtLeast(1)
    private val maxSpeechFrames = framesForMs(config.maxSpeechMs).coerceAtLeast(1)

    private var speechActive = false
    private var consecutiveEndSilence = 0
    private var speechFrames = 0

    override fun acceptPcm16Le(pcm: ByteArray): List<VadSpeechEvent> {
        if (pcm.isEmpty()) return emptyList()
        val output = ArrayList<VadSpeechEvent>()
        var sourceOffset = 0

        while (sourceOffset < pcm.size) {
            val copy = minOf(frameBytes - frameFill, pcm.size - sourceOffset)
            pcm.copyInto(frameBuffer, destinationOffset = frameFill, startIndex = sourceOffset, endIndex = sourceOffset + copy)
            frameFill += copy
            sourceOffset += copy

            if (frameFill == frameBytes) {
                val frame = frameBuffer.copyOf()
                frameFill = 0
                scoreFrame(frame, output)
            }
        }
        return output
    }

    private fun scoreFrame(frame: ByteArray, output: MutableList<VadSpeechEvent>) {
        pcm16LeToFloat(frame, floatFrame)
        val rawProbability = estimator.probability(floatFrame)
        val p = if (rawProbability.isFinite()) rawProbability.coerceIn(0f, 1f) else 0f

        if (!speechActive) {
            rememberPreRoll(frame)
            if (p >= config.startThreshold) {
                speechActive = true
                consecutiveEndSilence = 0
                output += VadSpeechEvent.SpeechStart
                if (preRollCapacity > 0) {
                    speechFrames = preRollFrames.size
                    preRollFrames.forEach { output += VadSpeechEvent.SpeechPcm(it) }
                    preRollFrames.clear()
                } else {
                    // The trigger window must never disappear just because pre-roll was
                    // configured to zero (useful in tests and low-memory variants).
                    speechFrames = 1
                    output += VadSpeechEvent.SpeechPcm(frame)
                }
                if (speechFrames >= maxSpeechFrames) endSpeech(output)
            }
            return
        }

        output += VadSpeechEvent.SpeechPcm(frame)
        speechFrames += 1

        if (p <= config.endThreshold) {
            consecutiveEndSilence += 1
        } else {
            consecutiveEndSilence = 0
        }

        if (consecutiveEndSilence >= endSilenceFrames || speechFrames >= maxSpeechFrames) {
            endSpeech(output)
        }
    }

    private fun endSpeech(output: MutableList<VadSpeechEvent>) {
        if (!speechActive) return
        speechActive = false
        consecutiveEndSilence = 0
        speechFrames = 0
        preRollFrames.clear()
        output += VadSpeechEvent.SpeechEnd
    }

    private fun rememberPreRoll(frame: ByteArray) {
        if (preRollCapacity <= 0) return
        while (preRollFrames.size >= preRollCapacity) preRollFrames.removeFirst()
        preRollFrames.add(frame)
    }

    /** Never splice VAD state from opposite sides of a real capture gap. */
    override fun resetForGap() {
        frameFill = 0
        preRollFrames.clear()
        speechActive = false
        consecutiveEndSilence = 0
        speechFrames = 0
    }

    override fun close() {
        resetForGap()
        estimator.close()
    }

    private fun framesForMs(ms: Int): Int {
        if (ms <= 0) return 0
        val samples = ms.toDouble() * config.sampleRate.toDouble() / 1000.0
        return ceil(samples / config.windowSamples.toDouble()).toInt()
    }

    private fun pcm16LeToFloat(source: ByteArray, destination: FloatArray) {
        var byteOffset = 0
        var sample = 0
        while (sample < destination.size) {
            val lo = source[byteOffset].toInt() and 0xff
            val hi = source[byteOffset + 1].toInt()
            val signed = (hi shl 8) or lo
            destination[sample] = signed.toShort().toFloat() / 32768.0f
            byteOffset += 2
            sample += 1
        }
    }
}
