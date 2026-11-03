package com.gildongmu.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import java.util.Locale

/**
 * 확인 항목: 안드로이드 표준 위치 API(LocationManager)로 위치·방향·속도를 받을 수 있는지.
 * FusedLocation SDK가 '준비 중'이라 내비를 켜지 않았을 때의 대안이 될 수 있는지 본다.
 */
class LocationCheck(private val context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var listening = false

    @Volatile
    var summary = "아직 시작 안 함"
        private set

    private val listener = LocationListener { location ->
        val text = describe(location)
        // 같은 값이 1초마다 반복되므로 바뀔 때만 기록한다.
        if (text != summary) EventLog.add("표준 위치", text)
        summary = text
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            summary = "위치 권한 없음"
            EventLog.add("표준 위치", summary)
            return
        }
        EventLog.add("표준 위치", "켜진 위치 제공자: ${locationManager.getProviders(true)}")
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { summary = describe(it) }
        listening = true
    }

    fun stop() {
        if (!listening) return
        locationManager.removeUpdates(listener)
        listening = false
    }

    companion object {
        fun describe(location: Location): String {
            val bearing = if (location.hasBearing()) "%.0f°".format(Locale.KOREA, location.bearing) else "없음"
            val speed = if (location.hasSpeed()) "%.1f km/h".format(Locale.KOREA, location.speed * 3.6f) else "없음"
            return "%.6f, %.6f · 진행 방향 %s · 속도 %s (%s)".format(
                Locale.KOREA, location.latitude, location.longitude, bearing, speed, location.provider,
            )
        }
    }
}
