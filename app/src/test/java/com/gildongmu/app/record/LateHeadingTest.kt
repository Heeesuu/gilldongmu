package com.gildongmu.app.record

import com.gildongmu.app.drive.DriveState
import org.junit.Assert.assertEquals
import org.junit.Test

class LateHeadingTest {

    // 녹음을 시작한 자리. 방향은 몰랐다.
    private val lat = 37.5672
    private val lon = 126.9790
    private val policy = LateHeading()

    private fun at(dLat: Double, heading: Double?) = DriveState(lat + dLat, lon, heading, speedKmh = 3.0, timeMs = 0L)

    @Test
    fun `녹음한 자리 가까이에서 방향이 잡히면 그 방향을 붙인다`() {
        // 위도 0.001도는 약 111m
        assertEquals(LateHeading.Decision.Use(10.0), policy.decide(lat, lon, 0L, at(0.001, 10.0), nowMs = 60_000L))
    }

    @Test
    fun `방향이 아직 없으면 10분까지 기다린다`() {
        assertEquals(LateHeading.Decision.Wait, policy.decide(lat, lon, 0L, at(0.0, null), nowMs = 9 * 60_000L))
        assertEquals(LateHeading.Decision.Wait, policy.decide(lat, lon, 0L, state = null, nowMs = 60_000L))
    }

    @Test
    fun `10분이 지나거나 방향 없이 300m 넘게 벗어나면 방향 없이 올린다`() {
        assertEquals(LateHeading.Decision.WithoutHeading, policy.decide(lat, lon, 0L, at(0.0, null), nowMs = 10 * 60_000L))
        assertEquals(LateHeading.Decision.WithoutHeading, policy.decide(lat, lon, 0L, state = null, nowMs = 10 * 60_000L))
        // 약 333m 떨어진 곳에서 잡힌 방향은 녹음한 자리의 방향이라고 보기 어렵다.
        assertEquals(LateHeading.Decision.WithoutHeading, policy.decide(lat, lon, 0L, at(0.003, 10.0), nowMs = 60_000L))
        assertEquals(LateHeading.Decision.WithoutHeading, policy.decide(lat, lon, 0L, at(0.003, null), nowMs = 60_000L))
    }
}
