package com.gildongmu.app

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent

/**
 * 확인 항목: 핸들(스티어링 휠) 버튼이 앱에 들어오는지.
 * Pleos 가이드는 핸들 버튼을 MediaSession으로 받으라고 안내하므로 세션 콜백과 화면 키 입력을 함께 기록한다.
 */
class MediaKeyCheck(context: Context) {

    private val session = MediaSession(context, "GildongmuKeys")

    @Volatile
    var summary = "아직 받은 버튼 없음"
        private set

    fun start() {
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = keyEventOf(mediaButtonIntent)
                if (event != null && event.action == KeyEvent.ACTION_DOWN) record("미디어 세션", event.keyCode)
                return true
            }
        })
        setPlaying(false)
        session.isActive = true
    }

    /** 미디어 버튼은 보통 소리를 내는 세션으로 가므로, 가짜 음악 재생 상태를 세션에 반영한다. */
    fun setPlaying(playing: Boolean) {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, 0L, 1f)
                .build(),
        )
    }

    fun record(source: String, keyCode: Int) {
        summary = "$source: ${KeyEvent.keyCodeToString(keyCode)}"
        EventLog.add("버튼", summary)
    }

    fun release() = session.release()

    private fun keyEventOf(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
}
