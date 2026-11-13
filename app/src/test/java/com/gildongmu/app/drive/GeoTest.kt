package com.gildongmu.app.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun `각도 차이는 0~180도로 돌려준다`() {
        assertEquals(2.0, Geo.angleDiff(359.0, 1.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(45.0, 225.0), 1e-9)
        assertEquals(0.0, Geo.angleDiff(90.0, 90.0), 1e-9)
    }

    @Test
    fun `방위는 북쪽 0 시계 방향이다`() {
        assertEquals(0.0, Geo.bearing(37.0, 127.0, 37.01, 127.0), 0.1)
        assertEquals(90.0, Geo.bearing(37.0, 127.0, 37.0, 127.01), 0.1)
        assertEquals(180.0, Geo.bearing(37.01, 127.0, 37.0, 127.0), 0.1)
    }

    @Test
    fun `위도 0_001도는 약 111m다`() {
        assertEquals(111.2, Geo.distanceMeters(37.0, 127.0, 37.001, 127.0), 0.5)
    }
}
