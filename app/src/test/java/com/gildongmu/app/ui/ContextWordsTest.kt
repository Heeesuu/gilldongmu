package com.gildongmu.app.ui

import com.gildongmu.app.data.Kind
import com.gildongmu.app.data.NearbyMessage
import com.gildongmu.app.data.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ContextWordsTest {

    // 기준: 2026-11-07(토) 오전 9시, 서울
    private val now = ZonedDateTime.of(2026, 11, 7, 9, 0, 0, 0, ZoneId.of("Asia/Seoul"))

    @Test
    fun `언제 남겼는지를 짧은 말로`() {
        assertEquals("방금", ContextWords.whenLeft("2026-11-07T00:00:30Z", now)) // 서울 08:59:30
        assertEquals("20분 전", ContextWords.whenLeft("2026-11-06T23:40:00Z", now))
        assertEquals("오늘 아침", ContextWords.whenLeft("2026-11-06T21:30:00Z", now)) // 서울 06:30
        assertEquals("어제 저녁", ContextWords.whenLeft("2026-11-06T10:00:00Z", now)) // 서울 19:00
        assertEquals("3일 전", ContextWords.whenLeft("2026-11-04T03:00:00Z", now))
        assertEquals("10월 2일", ContextWords.whenLeft("2026-10-02T03:00:00Z", now))
        assertNull(ContextWords.whenLeft("", now))
    }

    @Test
    fun `비 오는 날과 반응 수를 붙이고, 없는 것은 뺀다`() {
        val message = NearbyMessage(
            id = "m1", stage = Stage.FLOWER, kind = Kind.GREETING, lat = 0.0, lon = 0.0, heading = 0.0,
            distanceM = 100, etaSec = 9, durationMs = 6_400, thanksCount = 12, audioUrl = "",
            listenCount = 37, recordedAt = "2026-11-06T10:00:00Z", wiperOn = true, headlightOn = true,
        )
        assertEquals(listOf("어제 저녁", "비 오는 날", "고마워요 12", "37명이 들음"), ContextWords.chips(message, now))
        assertEquals(listOf("어제 저녁"), ContextWords.chips(message.copy(wiperOn = null, thanksCount = 0, listenCount = 0), now))
    }
}
