package com.gildongmu.app.guide

import com.gildongmu.app.data.NearbyMessage
import com.gildongmu.app.drive.DriveState
import com.gildongmu.app.drive.Geo
import com.gildongmu.app.drive.HeadingTracker

/** 앞쪽 한마디 하나를 지금 위치에서 다시 잰 값. `etaSec`은 시속 5km 미만이면 null이다. */
data class Approach(val message: NearbyMessage, val distanceM: Double, val etaSec: Double?)

/**
 * 언제 서버에 다시 묻고, 언제 "들을까요?"를 물을지 정한다 (docs/api.md 4장). 시각은 밀리초.
 * 서버 목록은 다음 조회까지 최대 10초쯤 묵으므로, 거리와 도착 예상 시간은 위치가 바뀔 때마다 앱이 다시 잰다.
 */
class GuidePolicy(
    private val pollDistanceM: Double = 100.0,
    private val promptEtaSec: Int = 10,
    val promptIntervalMs: Long = 5 * 60_000L,
) {
    data class PollMark(val lat: Double, val lon: Double, val timeMs: Long)

    /** 방향을 모르면 묻지 않는다. 처음이거나, 100m 이상 움직였거나, `next_poll_sec`가 지났으면 묻는다. */
    fun shouldPoll(last: PollMark?, state: DriveState, nowMs: Long, nextPollSec: Int): Boolean {
        if (state.heading == null) return false
        if (last == null) return true
        val moved = Geo.distanceMeters(last.lat, last.lon, state.lat, state.lon)
        return moved >= pollDistanceM || nowMs - last.timeMs >= nextPollSec * 1_000L
    }

    /** 서버 순서를 유지한 채, 아직 같은 방향·앞쪽에 있는 한마디만 남긴다. 이미 지나친 것과 `excluded`는 빠진다. */
    fun approaches(messages: List<NearbyMessage>, state: DriveState, excluded: Set<String>): List<Approach> {
        val heading = state.heading ?: return emptyList()
        return messages.filter { it.id !in excluded }.mapNotNull { m ->
            val sameDirection = Geo.angleDiff(m.heading, heading) <= SAME_DIRECTION_DEG
            val ahead = Geo.angleDiff(Geo.bearing(state.lat, state.lon, m.lat, m.lon), heading) <= AHEAD_DEG
            if (!sameDirection || !ahead) return@mapNotNull null
            val distance = Geo.distanceMeters(state.lat, state.lon, m.lat, m.lon)
            val eta = if (state.speedKmh < HeadingTracker.SLOW_KMH) null else distance / (state.speedKmh / 3.6)
            Approach(m, distance, eta)
        }
    }

    /** 첫 번째 한마디에 10초 안에 닿고, 마지막 안내에서 5분이 지났으면 그 한마디를 고른다. */
    fun pickPrompt(approaches: List<Approach>, nowMs: Long, lastPromptAtMs: Long?): Approach? {
        val first = approaches.firstOrNull() ?: return null
        val eta = first.etaSec ?: return null
        if (eta > promptEtaSec) return null
        if (lastPromptAtMs != null && nowMs - lastPromptAtMs < promptIntervalMs) return null
        return first
    }

    companion object {
        // 서버(server/internal/usecase)의 판정 기준과 같다.
        private const val SAME_DIRECTION_DEG = 45.0
        private const val AHEAD_DEG = 60.0
    }
}
