package com.friend.ios.phonemic.alwayson.speaker

import com.friend.ios.phonemic.alwayson.data.AlwaysOnSpeakerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeProfileMatcherTest {
    private val matcher = MeProfileMatcher()

    @Test
    fun fewerThanThreeConfirmedSamplesStaysUnknown() {
        val result = matcher.match(floatArrayOf(1f, 0f, 0f), listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(.99f, .01f, 0f)))
        assertEquals(AlwaysOnSpeakerIdentity.UNKNOWN, result.identity)
    }

    @Test
    fun consistentProfileMatchesMe() {
        val result = matcher.match(
            floatArrayOf(.98f, .08f, .01f),
            listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(.96f, .08f, 0f),
                floatArrayOf(.92f, .12f, .02f),
            ),
        )
        assertEquals(AlwaysOnSpeakerIdentity.ME, result.identity)
        assertNotNull(result.score)
    }

    @Test
    fun oneAccidentallyCloseSampleDoesNotOverrideBadCentroid() {
        val result = matcher.match(
            floatArrayOf(1f, 0f, 0f),
            listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f),
                floatArrayOf(0f, .8f, .2f),
            ),
        )
        assertEquals(AlwaysOnSpeakerIdentity.UNKNOWN, result.identity)
    }
}
