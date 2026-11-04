package com.gildongmu.app.guide

import com.gildongmu.app.data.Kind
import com.gildongmu.app.data.NearbyMessage
import com.gildongmu.app.data.Stage
import com.gildongmu.app.drive.DriveState
import com.gildongmu.app.drive.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePolicyTest {

    // 서버 테스트(server/internal/testsupport)와 같은 위치: 한마디를 남긴 곳과 약 190m 뒤, 약 190m 지나친 곳
    private val message = NearbyMessage(
        id = "m1", stage = Stage.SPROUT, kind = Kind.GREETING,
        lat = 37.5672, lon = 126.9790, heading = 45.0,
        distanceM = 190, etaSec = 17, durationMs = 6_400, thanksCount = 0, audioUrl = "/v1/messages/m1/audio",
    )
    private val policy = GuidePolicy()

    private fun at(lat: Double, lon: Double, heading: Double? = 45.0, speedKmh: Double = 40.0) =
        DriveState(lat, lon, heading, speedKmh, timeMs = 0L)

    private val behind = at(37.5660, 126.9775)
    private val passed = at(37.5684, 126.9805)

    @Test
    fun `같은 방향 앞쪽에 있으면 남기고 지금 위치에서 도착 시간을 다시 잰다`() {
        val result = policy.approaches(listOf(message), behind, emptySet())
        assertEquals(1, result.size)
        val distance = Geo.distanceMeters(behind.lat, behind.lon, message.lat, message.lon)
        assertEquals(distance / (40.0 / 3.6), result[0].etaSec!!, 0.01)
    }

    @Test
    fun `반대 방향이거나 이미 지나쳤거나 이미 물어본 한마디는 뺀다`() {
        assertTrue(policy.approaches(listOf(message), at(behind.lat, behind.lon, heading = 225.0), emptySet()).isEmpty())
        assertTrue(policy.approaches(listOf(message), passed, emptySet()).isEmpty())
        assertTrue(policy.approaches(listOf(message), behind, setOf("m1")).isEmpty())
    }

    @Test
    fun `방향을 모르는 한마디는 반대 방향에서 다가와도 남긴다`() {
        val unknown = message.copy(heading = null)
        assertEquals(1, policy.approaches(listOf(unknown), at(passed.lat, passed.lon, heading = 225.0), emptySet()).size)
        assertTrue("지나친 것은 여전히 뺀다", policy.approaches(listOf(unknown), passed, emptySet()).isEmpty())
    }

    @Test
    fun `10초 안에 닿을 때만 묻고 안내 사이는 5분을 둔다`() {
        val far = Approach(message, distanceM = 190.0, etaSec = 17.0)
        val near = Approach(message, distanceM = 100.0, etaSec = 9.0)
        val stopped = Approach(message, distanceM = 30.0, etaSec = null)

        assertNull(policy.pickPrompt(listOf(far), nowMs = 0L, lastPromptAtMs = null))
        assertNull("멈춰 있으면 도착 시간을 모르므로 묻지 않는다", policy.pickPrompt(listOf(stopped), 0L, null))
        assertEquals(near, policy.pickPrompt(listOf(near), nowMs = 0L, lastPromptAtMs = null))
        assertNull(policy.pickPrompt(listOf(near), nowMs = 4 * 60_000L, lastPromptAtMs = 0L))
        assertEquals(near, policy.pickPrompt(listOf(near), nowMs = 5 * 60_000L, lastPromptAtMs = 0L))
    }

    @Test
    fun `첫 번째 후보 순서는 서버 순서를 따른다`() {
        val flowerFar = Approach(message.copy(id = "flower"), distanceM = 400.0, etaSec = 36.0)
        val sproutNear = Approach(message.copy(id = "sprout"), distanceM = 100.0, etaSec = 9.0)
        assertNull("서버가 꽃을 먼저 주면 꽃이 가까워질 때까지 기다린다", policy.pickPrompt(listOf(flowerFar, sproutNear), 0L, null))
    }

    @Test
    fun `방향을 모르면 조회하지 않고, 100m 움직였거나 다음 조회 시간이 지나면 조회한다`() {
        assertFalse(policy.shouldPoll(null, behind.copy(heading = null), nowMs = 0L, nextPollSec = 10))
        assertTrue(policy.shouldPoll(null, behind, nowMs = 0L, nextPollSec = 10))

        val mark = GuidePolicy.PollMark(behind.lat, behind.lon, timeMs = 0L)
        assertFalse(policy.shouldPoll(mark, behind, nowMs = 9_000L, nextPollSec = 10))
        assertTrue(policy.shouldPoll(mark, behind, nowMs = 10_000L, nextPollSec = 10))
        val moved = at(behind.lat + 0.001, behind.lon) // 약 111m 북쪽
        assertTrue(policy.shouldPoll(mark, moved, nowMs = 1_000L, nextPollSec = 10))
    }
}
