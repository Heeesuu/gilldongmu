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
    /** 이 거리 안에 모인 한마디는 한 번에 묻는다. GPS 오차(10m 안팎)보다 넉넉하고, 시속 40km로 5초 안쪽이다. */
    private val groupRadiusM: Double = 50.0,
) {
    data class PollMark(val lat: Double, val lon: Double, val timeMs: Long)

    /** 방향을 모르면 묻지 않는다. 처음이거나, 100m 이상 움직였거나, `next_poll_sec`가 지났으면 묻는다. */
    fun shouldPoll(last: PollMark?, state: DriveState, nowMs: Long, nextPollSec: Int): Boolean {
        if (state.heading == null) return false
        if (last == null) return true
        val moved = Geo.distanceMeters(last.lat, last.lon, state.lat, state.lon)
        return moved >= pollDistanceM || nowMs - last.timeMs >= nextPollSec * 1_000L
    }

    /**
     * 서버 순서를 유지한 채, 아직 같은 방향·앞쪽에 있는 한마디만 남긴다. 이미 지나친 것과 `excluded`는 빠진다.
     * 방향을 모르는 한마디는 같은 방향 조건을 건너뛴다(서버와 같다).
     */
    fun approaches(messages: List<NearbyMessage>, state: DriveState, excluded: Set<String>): List<Approach> {
        val heading = state.heading ?: return emptyList()
        return messages.filter { it.id !in excluded }.mapNotNull { m ->
            val sameDirection = m.heading?.let { Geo.angleDiff(it, heading) <= SAME_DIRECTION_DEG } ?: true
            val ahead = Geo.angleDiff(Geo.bearing(state.lat, state.lon, m.lat, m.lon), heading) <= AHEAD_DEG
            if (!sameDirection || !ahead) return@mapNotNull null
            val distance = Geo.distanceMeters(state.lat, state.lon, m.lat, m.lon)
            val eta = if (state.speedKmh < HeadingTracker.SLOW_KMH) null else distance / (state.speedKmh / 3.6)
            Approach(m, distance, eta)
        }
    }

    /**
     * 가장 먼저 닿는(가장 가까운) 한마디에 10초 안에 닿고, 마지막 안내에서 5분이 지났으면 묻는다.
     * 그 한마디에서 50m 안에 모인 한마디도 함께 묶어 한 번에 묻는다 ("앞쪽에 한마디가 6건 있어요. 들을까요?").
     * 닿는 순서(가까운 순)로 돌려주고, 물을 게 없으면 빈 목록이다.
     */
    fun pickPrompt(approaches: List<Approach>, nowMs: Long, lastPromptAtMs: Long?): List<Approach> {
        val first = approaches.minByOrNull { it.distanceM } ?: return emptyList()
        val eta = first.etaSec ?: return emptyList()
        if (eta > promptEtaSec) return emptyList()
        if (lastPromptAtMs != null && nowMs - lastPromptAtMs < promptIntervalMs) return emptyList()
        return groupFrom(approaches)
    }

    /** 가장 가까운 한마디와, 거기서 50m 안에 모인 한마디. 닿는 순서로. Gleo로 "들려 줘"라고 할 때도 쓴다. */
    fun groupFrom(approaches: List<Approach>): List<Approach> {
        val first = approaches.minByOrNull { it.distanceM } ?: return emptyList()
        return approaches
            .filter { Geo.distanceMeters(first.message.lat, first.message.lon, it.message.lat, it.message.lon) <= groupRadiusM }
            .sortedBy { it.distanceM }
    }

    companion object {
        // 서버(server/internal/usecase)의 판정 기준과 같다.
        private const val SAME_DIRECTION_DEG = 45.0
        private const val AHEAD_DEG = 60.0
    }
}
