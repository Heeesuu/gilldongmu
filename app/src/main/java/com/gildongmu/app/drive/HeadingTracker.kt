package com.gildongmu.app.drive

/** 지금 차가 어디서 어느 방향으로 얼마나 빨리 가는지. `heading`은 아직 움직이지 않았으면 null이다. */
data class DriveState(
    val lat: Double,
    val lon: Double,
    val heading: Double?,
    val speedKmh: Double,
    val timeMs: Long,
)

/**
 * 연속된 GPS 위치로 진행 방향과 속도를 정한다 (docs/api.md 4장).
 * - GPS가 방향을 정확도 20° 이내로 주면 그 방향을 쓴다.
 * - 아니면 직전 기준 위치에서 8m 이상 움직였을 때 두 위치 사이의 방위를 쓴다.
 *   에뮬레이터의 `geo fix`는 방향 0°, 정확도 30°를 고정으로 보내서 이 경로를 탄다.
 * - 시속 5km 미만이면 GPS 흔들림에 방향이 튀지 않도록 마지막 방향을 유지한다.
 * 안드로이드 Location 대신 숫자만 받아서 JVM 단위 테스트로 확인할 수 있게 했다.
 */
class HeadingTracker {

    data class Fix(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
        val speedMps: Float? = null,
        val bearing: Float? = null,
        val bearingAccuracy: Float? = null,
    )

    private var heading: Double? = null
    private var previous: Fix? = null
    private var anchor: Fix? = null

    fun update(fix: Fix): DriveState {
        val speedKmh = fix.speedMps?.let { it * 3.6 } ?: computedSpeedKmh(previous, fix)
        previous = fix

        if (speedKmh < SLOW_KMH) {
            // 멈춰 있는 동안의 위치는 방향 계산에 쓰지 않는다. 다시 움직이면 여기서부터 잰다.
            anchor = fix
        } else if (fix.bearing != null && fix.bearingAccuracy != null && fix.bearingAccuracy <= MAX_BEARING_ACCURACY) {
            heading = fix.bearing.toDouble()
            anchor = fix
        } else {
            val from = anchor
            if (from == null) {
                anchor = fix
            } else if (Geo.distanceMeters(from.lat, from.lon, fix.lat, fix.lon) >= MIN_MOVE_M) {
                heading = Geo.bearing(from.lat, from.lon, fix.lat, fix.lon)
                anchor = fix
            }
        }
        return DriveState(fix.lat, fix.lon, heading, speedKmh, fix.timeMs)
    }

    private fun computedSpeedKmh(from: Fix?, to: Fix): Double {
        if (from == null || to.timeMs <= from.timeMs) return 0.0
        val meters = Geo.distanceMeters(from.lat, from.lon, to.lat, to.lon)
        return meters / ((to.timeMs - from.timeMs) / 1_000.0) * 3.6
    }

    companion object {
        const val SLOW_KMH = 5.0
        private const val MIN_MOVE_M = 8.0
        private const val MAX_BEARING_ACCURACY = 20f
    }
}
