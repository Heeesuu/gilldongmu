package com.gildongmu.app.check

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.gildongmu.app.EventLog
import java.io.File

/**
 * 시연 영상용 가짜 내비 안내. 에뮬레이터의 내비 앱은 GPS를 따라 안내 음성을 내지 않아서, 그 자리를 대신한다.
 * - 보내기: `adb shell am broadcast --user 10 -n com.gildongmu.app/.check.DemoNaviReceiver [--es file navi.ogg]`
 * - 소리: 앱 저장소 files/demo/ 안의 파일(기본 navi.ogg). 내비 안내 용도로 틀고 잠깐 포커스(GAIN_TRANSIENT_MAY_DUCK)를 받는다.
 *   확인 화면의 "가짜 내비 안내"(삑 소리)와 용도가 같아서, 길동무는 한마디를 멈췄다가 안내가 끝나면 이어 튼다.
 * debug 빌드에만 들어가고, adb 셸만 보낼 수 있게 매니페스트에서 DUMP 권한을 요구한다.
 */
class DemoNaviReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 파일 이름만 받는다(다른 폴더를 가리키지 못하게).
        val name = File(intent.getStringExtra("file") ?: "navi.ogg").name
        DemoNavi.play(context.applicationContext, File(File(context.filesDir, "demo"), name))
    }
}

/** 한 번에 하나만 튼다. 시연 중에는 길동무 서비스가 떠 있어서 수신기가 끝나도 프로세스가 살아 있다. */
private object DemoNavi {

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    fun play(context: Context, file: File) {
        if (player != null) return
        if (!file.isFile) {
            EventLog.add("시연", "가짜 내비 안내 파일이 없어요 (files/demo/${file.name})")
            return
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audioManager.requestAudioFocus(request)
        focus = request
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(file.path)
            mp.prepare()
            mp.setOnCompletionListener { finish(audioManager, "끝") }
            mp.start()
            EventLog.add("시연", "가짜 내비 안내 시작: ${file.name} (%.1f초)".format(mp.duration / 1_000.0))
        }.onFailure {
            EventLog.add("시연", "가짜 내비 안내를 틀 수 없어요 (${it.message})")
            finish(audioManager, null)
        }
    }

    private fun finish(audioManager: AudioManager, how: String?) {
        player?.release()
        player = null
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        how?.let { EventLog.add("시연", "가짜 내비 안내 $it") }
    }
}
