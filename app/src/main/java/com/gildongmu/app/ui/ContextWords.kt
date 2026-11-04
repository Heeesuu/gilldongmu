package com.gildongmu.app.ui

import com.gildongmu.app.data.NearbyMessage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 안내 카드의 작은 표시: 언제·어떤 날 남겼고 몇 명이 반응했는지 ("어제 저녁 · 비 오는 날 · 고마워요 12").
 * 남긴 사람은 드러내지 않는다(닉네임은 내 기기에서만 보임).
 */
object ContextWords {

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    fun chips(m: NearbyMessage, now: ZonedDateTime = ZonedDateTime.now(seoul)): List<String> = listOfNotNull(
        whenLeft(m.recordedAt, now),
        "비 오는 날".takeIf { m.wiperOn == true },
        "고마워요 ${m.thanksCount}".takeIf { m.thanksCount > 0 },
        "${m.listenCount}명이 들음".takeIf { m.listenCount > 0 },
    )

    /** "방금", "20분 전", "오늘 아침", "어제 저녁", "3일 전", "10월 2일". 시각을 못 읽으면 null. */
    fun whenLeft(recordedAt: String, now: ZonedDateTime): String? {
        val at = runCatching { Instant.parse(recordedAt).atZone(seoul) }.getOrNull() ?: return null
        val minutes = Duration.between(at, now).toMinutes()
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), now.withZoneSameInstant(seoul).toLocalDate())
        return when {
            minutes < 1 -> "방금"
            minutes < 60 -> "${minutes}분 전"
            days == 0L -> "오늘 ${period(at.hour)}"
            days == 1L -> "어제 ${period(at.hour)}"
            days < 7 -> "${days}일 전"
            else -> "${at.monthValue}월 ${at.dayOfMonth}일"
        }
    }

    private fun period(hour: Int) = when (hour) {
        in 5..10 -> "아침"
        in 11..13 -> "낮"
        in 14..17 -> "오후"
        in 18..21 -> "저녁"
        else -> "밤"
    }
}
