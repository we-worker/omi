package com.friend.ios.phonemic.alwayson.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ClusterAudioAssemblerTest {
    @Test
    fun concatenatesOnlySpeakerRegionsInTimelineOrder() {
        val samples = FloatArray(100) { it.toFloat() }
        val output = ClusterAudioAssembler.assemble(
            samples = samples,
            sampleRate = 100,
            regions = listOf(
                DiarizedRegion(500, 700, 0),
                DiarizedRegion(100, 300, 0),
            ),
            maxDurationMs = 1_000,
        )
        val expected = (10 until 30).map(Int::toFloat).plus((50 until 70).map(Int::toFloat)).toFloatArray()
        assertArrayEquals(expected, output, 0f)
    }
}
