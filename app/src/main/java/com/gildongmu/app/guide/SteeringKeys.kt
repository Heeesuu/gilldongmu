package com.gildongmu.app.guide

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent

/**
 * 핸들(스티어링 휠) 버튼을 MediaSession으로 받는다 (Pleos 가이드 방식).
 * 안내·재생 중에만 세션을 켜서, 그 밖의 시간에는 버튼이 원래대로 음악 앱으로 가게 한다.
 */
class SteeringKeys(context: Context, private val onKey: (Key) -> Unit) {

    enum class Key { PLAY, NEXT }

    private val session = MediaSession(context, "GildongmuGuide").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = keyEventOf(mediaButtonIntent) ?: return false
                if (event.action == KeyEvent.ACTION_DOWN) keyOf(event.keyCode)?.let(onKey)
                return true
            }
        })
    }

    fun activate() {
        setState(PlaybackState.STATE_PLAYING)
        session.isActive = true
    }

    fun deactivate() {
        setState(PlaybackState.STATE_PAUSED)
        session.isActive = false
    }

    fun release() = session.release()

    private fun setState(state: Int) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP,
                )
                .setState(state, 0L, 1f)
                .build(),
        )
    }

    private fun keyEventOf(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    companion object {
        /** 재생 계열은 '듣기', 다음 곡·정지 계열은 '넘기기'로 본다. 화면 키 입력도 같은 표를 쓴다. */
        fun keyOf(keyCode: Int): Key? = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Key.PLAY
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> Key.NEXT
            else -> null
        }
    }
}
