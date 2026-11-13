package com.gildongmu.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 화면 밖에서도 안내가 이어지게 하는 포그라운드 서비스. 알림 하나를 띄워 두고 [Core]를 살려 둔다.
 * 내비를 전체 화면으로 띄워 길동무가 뒤로 숨어도 위치를 받고, "들어 볼까요?"를 묻고, 핸들 버튼·Gleo 명령을 받는다.
 * 서비스 종류는 권한에 맞춘다(안드로이드 14: 권한 없이 그 종류로 시작하면 앱이 꺼진다).
 * - 위치(location): 위치 권한이 있을 때
 * - 마이크(microphone): 마이크 권한이 있을 때. 화면 밖에서 Gleo로 녹음하거나 말로 대답할 때 쓴다
 * - 미디어 재생(mediaPlayback): 항상. 화면 밖에서 한마디를 튼다
 * 화면이 보일 때 시작해야 한다(화면 밖에서 위치·마이크 종류로 새로 시작할 수 없다).
 */
class GuideService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "길동무 안내", NotificationManager.IMPORTANCE_LOW).apply {
                description = "화면을 닫아도 앞쪽 길동무를 알려 주는 동안 보여요."
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            EventLog.add("앱", "알림에서 안내를 껐어요")
            (application as GildongmuApp).stopCore()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 화면이 권한을 새로 받았을 때도 불린다. 서비스 종류를 다시 맞추고 위치를 받기 시작한다.
        promote()
        (application as GildongmuApp).core().startTracking()
        // 시스템이 멈춘 서비스를 화면 없이 다시 띄우면 위치 종류로 시작할 수 없으므로, 다시 띄우지 않는다.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIFICATION_ID, notification(), types)
        } catch (e: RuntimeException) {
            // 화면 밖에서 불려 위치·마이크 종류를 쓸 수 없을 때: 소리만이라도 이어 간다.
            EventLog.add("앱", "화면 밖 안내를 시작하지 못했어요: ${e.message}")
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GuideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_route)
            .setContentTitle("길동무가 앞쪽 길을 살피고 있어요")
            .setContentText("화면을 닫아도 길동무가 있으면 알려 드려요. 핸들 재생 버튼으로 들을 수 있어요.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "끄기", stop).build())
            .build()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL = "guide"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.gildongmu.app.action.STOP_GUIDE"

        /** 화면이 보일 때 부른다. 이미 돌고 있으면 서비스 종류만 다시 맞춘다. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuideService::class.java))
        }
    }
}
