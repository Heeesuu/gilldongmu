package com.gildongmu.app.drive

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 거리·방위·각도 차이. 서버(server/internal/domain/geo.go)와 같은 식을 쓴다. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 두 지점 사이의 거리(하버사인)를 미터로 돌려준다. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** 첫 지점에서 둘째 지점을 바라보는 방위를 0~360도로 돌려준다. 북쪽 0, 시계 방향. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** 두 방향의 차이를 0~180도로 돌려준다. 359°와 1°의 차이는 2°다. */
    fun angleDiff(a: Double, b: Double): Double = abs((a - b + 540) % 360 - 180)
}
