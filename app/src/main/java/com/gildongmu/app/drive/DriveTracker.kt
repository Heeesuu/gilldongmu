package com.gildongmu.app.drive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.gildongmu.app.EventLog
import java.util.Locale

/**
 * 표준 GPS(LocationManager)로 1초마다 위치를 받아 진행 방향·속도를 계산한다.
 * 에뮬레이터에서는 NaviHelper 위치가 움직이지 않고 차량 속도도 0이라 표준 GPS를 쓴다.
 */
class DriveTracker(private val context: Context, private val onUpdate: (DriveState) -> Unit) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val headingTracker = HeadingTracker()
    private var listening = false

    @Volatile
    var latest: DriveState? = null
        private set

    private val listener = LocationListener { location ->
        val hadHeading = latest?.heading != null
        val state = headingTracker.update(location.toFix())
        latest = state
        if (!hadHeading && state.heading != null) EventLog.add("주행", "진행 방향을 잡았어요: ${describe(state)}")
        onUpdate(state)
    }

    /** 위치 권한이 없으면 false를 돌려준다. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add("주행", "위치 권한이 없어 시작하지 못했어요")
            return false
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        listening = true
        EventLog.add("주행", "GPS 위치 받기 시작")
        return true
    }

    fun stop() {
        if (!listening) return
        locationManager.removeUpdates(listener)
        listening = false
    }

    private fun Location.toFix() = HeadingTracker.Fix(
        lat = latitude,
        lon = longitude,
        timeMs = time,
        speedMps = if (hasSpeed()) speed else null,
        bearing = if (hasBearing()) bearing else null,
        bearingAccuracy = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    )

    companion object {
        fun describe(state: DriveState): String {
            val heading = state.heading?.let { "%.0f°".format(Locale.KOREA, it) } ?: "아직 모름"
            return "%.5f, %.5f · 방향 %s · 시속 %.0fkm".format(Locale.KOREA, state.lat, state.lon, heading, state.speedKmh)
        }
    }
}
