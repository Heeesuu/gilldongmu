package com.gildongmu.app.record

import com.gildongmu.app.drive.DriveState
import com.gildongmu.app.drive.Geo

/**
 * 녹음을 시작할 때 방향을 몰랐던 한마디에 나중에 방향을 붙일지 정한다 (docs/api.md 4장).
 * 막힌 길 한가운데서 앱을 켜도 녹음은 바로 하고, 위치는 녹음을 시작한 자리 그대로 둔다.
 * - 녹음한 자리에서 300m 안에서 방향이 잡히면 그 방향을 쓴다.
 * - 10분이 지나거나 방향 없이 300m 넘게 벗어나면 방향 없이 올린다. 서버는 양쪽 방향 모두에 들려준다.
 * 안드로이드 없이 JVM 단위 테스트로 확인할 수 있게 숫자만 받는다. 시각은 밀리초.
 */
class LateHeading(
    private val maxDistanceM: Double = 300.0,
    private val maxWaitMs: Long = 10 * 60_000L,
) {
    sealed interface Decision {
        /** 아직 기다린다. */
        data object Wait : Decision

        /** 이 방향을 붙여 올린다. */
        data class Use(val heading: Double) : Decision

        /** 방향 없이 올린다. */
        data object WithoutHeading : Decision
    }

    fun decide(lat: Double, lon: Double, recordedAtMs: Long, state: DriveState?, nowMs: Long): Decision {
        val distance = state?.let { Geo.distanceMeters(lat, lon, it.lat, it.lon) }
        val heading = state?.heading
        return when {
            heading != null && distance != null && distance <= maxDistanceM -> Decision.Use(heading)
            nowMs - recordedAtMs >= maxWaitMs -> Decision.WithoutHeading
            distance != null && distance > maxDistanceM -> Decision.WithoutHeading
            else -> Decision.Wait
        }
    }
}
