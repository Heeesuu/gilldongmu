package com.gildongmu.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 확인 항목: 창을 닫은 뒤에도 위치를 받으며 살아 있는지(소리만 쓰는 방식의 전제 조건).
 * 5초마다 하트비트를 세고, 위치를 받은 횟수를 함께 기록한다.
 */
class BackgroundCheckService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val heartbeat = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % 6 == 0) EventLog.add("백그라운드", "살아 있음 ${ticks * 5}초째, 위치 ${locationUpdates}번 받음")
            handler.postDelayed(this, 5_000L)
        }
    }

    private val locationListener = LocationListener { location ->
        locationUpdates++
        lastLocation = LocationCheck.describe(location)
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        running = true
        startedAt = System.currentTimeMillis()
        ticks = 0
        locationUpdates = 0
        requestLocation()
        handler.postDelayed(heartbeat, 5_000L)
        EventLog.add("백그라운드", "서비스 시작")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        running = false
        EventLog.add("백그라운드", "서비스 종료 (${ticks * 5}초 동안 살아 있었음)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add("백그라운드", "위치 권한 없음")
            return
        }
        getSystemService(LocationManager::class.java)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, locationListener, Looper.getMainLooper())
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "백그라운드 확인", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("길동무 백그라운드 확인 중")
            .setContentText("창을 닫아도 위치를 받는지 확인합니다")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "background_check"
        private const val NOTIFICATION_ID = 1

        @Volatile private var running = false
        @Volatile private var startedAt = 0L
        @Volatile private var ticks = 0
        @Volatile private var locationUpdates = 0
        @Volatile private var lastLocation = "-"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, BackgroundCheckService::class.java))
            } catch (e: Exception) {
                EventLog.add("백그라운드", "시작 실패: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundCheckService::class.java))
        }

        fun summary(): String {
            if (!running) return "꺼짐"
            val seconds = (System.currentTimeMillis() - startedAt) / 1_000
            return "켜짐 · ${seconds}초째 · 하트비트 ${ticks}번 · 위치 ${locationUpdates}번 받음\n마지막 위치: $lastLocation"
        }
    }
}
