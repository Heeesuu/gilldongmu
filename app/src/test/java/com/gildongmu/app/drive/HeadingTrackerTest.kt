package com.gildongmu.app.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingTrackerTest {

    // 북동쪽(45°)으로 시속 36km(초속 10m)로 가는 1초 간격 위치
    private fun northEast(step: Int, speedMps: Float? = 10f, bearing: Float? = null, accuracy: Float? = null): HeadingTracker.Fix {
        val meters = step * 10.0
        val lat = 37.5660 + meters * Math.cos(Math.toRadians(45.0)) / 111_320
        val lon = 126.9775 + meters * Math.sin(Math.toRadians(45.0)) / (111_320 * Math.cos(Math.toRadians(37.566)))
        return HeadingTracker.Fix(lat, lon, step * 1_000L, speedMps, bearing, accuracy)
    }

    @Test
    fun `에뮬레이터처럼 방향 0도 정확도 30도를 고정으로 주면 두 위치의 방위를 쓴다`() {
        val tracker = HeadingTracker()
        assertNull("첫 위치만으로는 방향을 모른다", tracker.update(northEast(0, bearing = 0f, accuracy = 30f)).heading)
        val state = tracker.update(northEast(1, bearing = 0f, accuracy = 30f))
        assertEquals(45.0, state.heading!!, 1.0)
        assertEquals(36.0, state.speedKmh, 0.1)
    }

    @Test
    fun `GPS가 정확도와 함께 방향을 주면 그 방향을 쓴다`() {
        val tracker = HeadingTracker()
        val state = tracker.update(northEast(0, bearing = 50f, accuracy = 5f))
        assertEquals(50.0, state.heading!!, 0.001)
    }

    @Test
    fun `시속 5km 미만이면 마지막 방향을 유지한다`() {
        val tracker = HeadingTracker()
        tracker.update(northEast(0))
        tracker.update(northEast(1))
        // 멈춘 상태에서 GPS가 남쪽으로 20m 튀어도 방향은 그대로다.
        val jitter = northEast(2, speedMps = 0f).let { it.copy(lat = it.lat - 20.0 / 111_320) }
        val state = tracker.update(jitter)
        assertEquals(45.0, state.heading!!, 1.0)
    }

    @Test
    fun `속도를 주지 않으면 이동 거리로 계산한다`() {
        val tracker = HeadingTracker()
        tracker.update(northEast(0, speedMps = null))
        val state = tracker.update(northEast(1, speedMps = null))
        assertEquals(36.0, state.speedKmh, 0.5)
    }
}
