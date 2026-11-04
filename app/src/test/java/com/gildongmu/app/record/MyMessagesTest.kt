package com.gildongmu.app.record

import com.gildongmu.app.data.Kind
import com.gildongmu.app.data.MyMessage
import com.gildongmu.app.data.Review
import com.gildongmu.app.data.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyMessagesTest {

    private fun mine(stage: Stage, listen: Int, thanks: Int) = MyMessage(
        id = "m$listen$thanks", stage = stage, kind = Kind.GREETING, review = Review.CLEAN,
        durationMs = 6_400, listenCount = listen, thanksCount = thanks, createdAt = "2026-11-07T23:50:03Z",
    )

    @Test
    fun `꽃 소식은 들은 사람과 고마워한 사람을 모두 더한다`() {
        val summary = MyMessages.bloomSummary(listOf(mine(Stage.FLOWER, 32, 5), mine(Stage.SPROUT, 3, 1)))
        assertEquals("한마디 2개를 남겼어요. 35명이 들었고 6명이 고마워했어요. 꽃이 1송이 피었어요.", summary)
    }

    @Test
    fun `꽃이 없으면 꽃 문장은 빼고, 남긴 게 없으면 소식도 없다`() {
        assertEquals(
            "한마디 1개를 남겼어요. 0명이 들었고 0명이 고마워했어요.",
            MyMessages.bloomSummary(listOf(mine(Stage.SEED, 0, 0))),
        )
        assertNull(MyMessages.bloomSummary(emptyList()))
    }
}
