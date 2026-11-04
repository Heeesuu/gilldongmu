package com.gildongmu.app.check

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.gildongmu.app.EventLog
import kotlin.math.PI
import kotlin.math.sin

/**
 * 확인 항목: 한마디를 재생할 때 음악이 줄어드는지(덕), 내비 안내가 나오면 한마디가 멈추는지.
 * 실제 음원 대신 높이가 다른 삐 소리로 음악·한마디·내비 안내를 흉내 낸다.
 */
class AudioCheck(context: Context, private val onMusicPlaying: (Boolean) -> Unit) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val events = ArrayDeque<String>()

    val music = Player("가짜 음악(MEDIA)", AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN, seconds = 0, frequency = 262.0)
    val messageAsMedia = Player("한마디(MEDIA)", AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, seconds = 4, frequency = 523.0, pauseWhenDucked = true)
    val messageAsAssistant = Player("한마디(ASSISTANT)", AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, seconds = 4, frequency = 659.0, pauseWhenDucked = true)
    val navigation = Player("가짜 내비 안내", AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.CONTENT_TYPE_SPEECH,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, seconds = 2, frequency = 880.0)

    private val players = listOf(music, messageAsMedia, messageAsAssistant, navigation)

    fun summary(): String {
        val states = players.joinToString("\n") { "${it.name}: ${it.state}" }
        val recent = synchronized(events) { events.joinToString("\n") }
        return "$states\n— 최근 포커스 이벤트 —\n$recent"
    }

    fun stopAll() = players.forEach { it.stop("모두 정지") }

    private fun record(message: String) {
        synchronized(events) {
            events.addFirst(message)
            while (events.size > 8) events.removeLast()
        }
        EventLog.add("오디오", message)
    }

    inner class Player(
        val name: String,
        private val usage: Int,
        private val contentType: Int,
        private val focusGain: Int,
        private val seconds: Int,
        private val frequency: Double,
        private val pauseWhenDucked: Boolean = false,
    ) {
        @Volatile
        var state = "정지"
            private set

        private var track: AudioTrack? = null
        private var focusRequest: AudioFocusRequest? = null
        private val autoStop = Runnable { stop("재생 끝") }

        fun play() {
            if (track != null) return
            val attributes = AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(attributes)
                // 한마디는 내비 안내가 나오면 직접 멈춘다. 이 값이 없으면 시스템이 소리만 줄이고 알려 주지 않는다.
                .setWillPauseWhenDucked(pauseWhenDucked)
                .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
                .build()
            val result = audioManager.requestAudioFocus(request)
            record("$name 포커스 요청 → ${requestResultName(result)}")
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                state = "포커스 못 받음"
                return
            }
            focusRequest = request
            // seconds = 0 이면 멈출 때까지 반복(음악 역할)
            track = buildTone(attributes, frequency, if (seconds == 0) 2 else seconds, loop = seconds == 0).also { it.play() }
            state = "재생 중"
            if (this === music) onMusicPlaying(true)
            if (seconds > 0) handler.postDelayed(autoStop, seconds * 1_000L)
        }

        fun stop(reason: String) {
            handler.removeCallbacks(autoStop)
            val current = track ?: return
            current.stop()
            current.release()
            track = null
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
            state = "정지 ($reason)"
            if (this === music) onMusicPlaying(false)
        }

        private fun onFocusChange(change: Int) {
            record("$name ← ${focusChangeName(change)}")
            val current = track ?: return
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (pauseWhenDucked) {
                    current.pause()
                    state = "내비 안내로 잠시 멈춤"
                } else {
                    current.setVolume(0.2f)
                    state = "소리 줄임(덕)"
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    current.pause()
                    state = "잠시 멈춤"
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    current.setVolume(1f)
                    current.play()
                    state = "재생 중"
                }
                AudioManager.AUDIOFOCUS_LOSS -> stop("포커스 잃음")
            }
        }
    }

    private fun buildTone(attributes: AudioAttributes, frequency: Double, durationSec: Int, loop: Boolean): AudioTrack {
        val sampleRate = 22_050
        val count = sampleRate * durationSec
        val pcm = ShortArray(count) { i ->
            (sin(2 * PI * frequency * i / sampleRate) * 0.3 * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(count * 2)
            .build()
        track.write(pcm, 0, count)
        if (loop) track.setLoopPoints(0, count, -1)
        return track
    }

    private fun requestResultName(result: Int) = when (result) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "허용"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "보류"
        else -> "거절"
    }

    private fun focusChangeName(change: Int) = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "다시 받음(GAIN)"
        AudioManager.AUDIOFOCUS_LOSS -> "잃음(LOSS)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "잠시 잃음(LOSS_TRANSIENT)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "소리 줄여도 됨(CAN_DUCK)"
        else -> "기타($change)"
    }
}
