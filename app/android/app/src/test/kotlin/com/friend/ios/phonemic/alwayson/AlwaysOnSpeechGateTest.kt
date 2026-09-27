package com.friend.ios.phonemic.alwayson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnSpeechGateTest {
    private class FakeEstimator(probabilities: List<Float>) : SpeechProbabilityEstimator {
        private val values = ArrayDeque(probabilities)
        var calls = 0
            private set

        override fun probability(samples: FloatArray): Float {
            calls += 1
            return if (values.isEmpty()) 0f else values.removeFirst()
        }
    }

    private val testConfig = SpeechGateConfig(
        sampleRate = 16_000,
        windowSamples = 512,
        preRollMs = 64,
        startThreshold = 0.55f,
        endThreshold = 0.35f,
        endSilenceMs = 64,
        maxSpeechMs = 10_000,
    )

    @Test
    fun `idle silence is dropped`() {
        val estimator = FakeEstimator(listOf(0.01f, 0.02f, 0.03f))
        val gate = AlwaysOnSpeechGate(estimator, testConfig)

        val out = gate.acceptPcm16Le(frames(1, 2, 3))

        assertTrue(out.isEmpty())
        assertEquals(3, estimator.calls)
    }

    @Test
    fun `speech start emits boundary and configured pre-roll`() {
        val estimator = FakeEstimator(listOf(0.01f, 0.02f, 0.9f))
        val gate = AlwaysOnSpeechGate(estimator, testConfig)

        val out = gate.acceptPcm16Le(frames(10, 20, 30))

        assertTrue(out.first() === VadSpeechEvent.SpeechStart)
        assertEquals(listOf(20, 30), pcmValues(out))
    }

    @Test
    fun `speech keeps trailing silence then emits end`() {
        val estimator = FakeEstimator(listOf(0.9f, 0.8f, 0.1f, 0.1f, 0.1f))
        val gate = AlwaysOnSpeechGate(estimator, testConfig)

        val out = gate.acceptPcm16Le(frames(1, 2, 3, 4, 5))

        assertEquals(listOf(1, 2, 3, 4), pcmValues(out))
        assertEquals(1, out.count { it === VadSpeechEvent.SpeechStart })
        assertEquals(1, out.count { it === VadSpeechEvent.SpeechEnd })
    }

    @Test
    fun `arbitrary AudioRecord chunk sizes are reassembled into VAD windows`() {
        val estimator = FakeEstimator(listOf(0.9f, 0.8f))
        val gate = AlwaysOnSpeechGate(estimator, testConfig)
        val twoFrames = frames(7, 8)

        val first = gate.acceptPcm16Le(twoFrames.copyOfRange(0, 1300))
        val second = gate.acceptPcm16Le(twoFrames.copyOfRange(1300, twoFrames.size))

        assertEquals(listOf(7), pcmValues(first))
        assertEquals(listOf(8), pcmValues(second))
        assertEquals(2, estimator.calls)
    }

    @Test
    fun `gap reset drops partial window and stale pre-roll`() {
        val estimator = FakeEstimator(listOf(0.1f, 0.9f))
        val gate = AlwaysOnSpeechGate(estimator, testConfig)
        val frame = frame(9)

        gate.acceptPcm16Le(frame.copyOfRange(0, 500))
        gate.resetForGap()
        val out = gate.acceptPcm16Le(frames(10, 11))

        assertEquals(2, estimator.calls)
        assertEquals(listOf(10, 11), pcmValues(out))
    }

    @Test
    fun `hard max speech duration emits an end boundary`() {
        val estimator = FakeEstimator(List(4) { 0.9f })
        val gate = AlwaysOnSpeechGate(
            estimator,
            testConfig.copy(preRollMs = 0, maxSpeechMs = 64),
        )

        val out = gate.acceptPcm16Le(frames(1, 2, 3, 4))

        assertTrue(out.count { it === VadSpeechEvent.SpeechEnd } >= 1)
        assertTrue(out.count { it === VadSpeechEvent.SpeechStart } >= 1)
    }

    private fun pcmValues(events: List<VadSpeechEvent>): List<Int> =
        events.filterIsInstance<VadSpeechEvent.SpeechPcm>().map { it.pcm16Le[0].toInt() and 0xff }

    private fun frame(value: Int): ByteArray = ByteArray(512 * 2) { value.toByte() }

    private fun frames(vararg values: Int): ByteArray {
        val result = ByteArray(values.size * 512 * 2)
        values.forEachIndexed { index, value ->
            frame(value).copyInto(result, destinationOffset = index * 512 * 2)
        }
        return result
    }
}
