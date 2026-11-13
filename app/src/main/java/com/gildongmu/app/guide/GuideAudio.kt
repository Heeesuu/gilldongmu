package com.gildongmu.app.guide

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gildongmu.app.EventLog
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * "들을까요?" 알림음과 한마디 재생을 맡는다. 오디오 설정은 에뮬레이터에서 확인한 값이다 (docs/api.md 4장).
 * - 미디어 용도 + 잠깐 포커스(GAIN_TRANSIENT_MAY_DUCK): 그동안 음악은 멈추거나 줄어든다.
 * - 내비 안내가 오면 직접 멈추고(setWillPauseWhenDucked), 안내가 끝나면 이어서 튼다.
 * 포커스는 알림음부터 재생이 끝날 때까지 한 번만 잡는다. 중간에 놓으면 음악이 다시 나오면서
 * 핸들 버튼이 음악 앱으로 가 버리기 때문이다.
 * "들을까요?" 문장은 [PromptVoice]가 Pleos TTS(차 안 엔진)로 읽는다. 읽을 수 없으면 알림음만 낸다.
 * 문장을 읽는 중에 내비 안내가 끼어들면 읽기를 멈추고, 안내가 끝나면 처음부터 다시 읽는다.
 */
class GuideAudio(context: Context, private val onFocusLost: () -> Unit) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        // 이 값이 없으면 시스템이 소리만 줄이고 알려 주지 않는다.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
        .build()

    private var hasFocus = false
    private var player: MediaPlayer? = null
    private var pausedByFocus = false
    private var onDone: ((completed: Boolean, playedMs: Long) -> Unit)? = null

    // 길이 제한은 실제로 소리가 난 시간만 센다. 내비 안내로 멈춘 동안은 세지 않는다.
    private var limitMs = 0L
    private var playedBeforeMs = 0L
    private var playingSinceMs: Long? = null
    private val lengthLimit = Runnable {
        EventLog.add("오디오", "정해진 길이가 지나 멈췄어요")
        finish(completed = true, playedMs = limitMs)
    }

    private val voice = PromptVoice(context, attributes)

    @Volatile
    var state = "조용함"
        private set

    val positionMs: Long
        get() = player?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: 0L

    /**
     * 내비 안내·통화·음성 비서가 지금 말하고 있는가. 그동안은 "들어 볼까요?"를 묻지 않는다.
     * 차의 오디오 규칙상 미디어 소리가 내비 안내와 겹쳐 나올 수 있어서, 묻기 전에 직접 확인한다.
     * 지금 재생 중인 소리의 용도만 본다(다른 앱의 내용은 볼 수 없다).
     */
    fun othersSpeaking(): Boolean {
        if (audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) return true
        return audioManager.activePlaybackConfigurations.any { it.audioAttributes.usage in SPEAKING_USAGES }
    }

    /** 안내를 시작할 때 한 번 부른다. 포커스를 못 받으면(통화 중 등) false. */
    fun begin(): Boolean {
        if (hasFocus) return true
        val result = audioManager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        EventLog.add("오디오", "포커스 요청 → ${if (hasFocus) "허용" else "거절($result)"}")
        return hasFocus
    }

    /** 높이가 다른 짧은 소리 두 번. 안내 음성 파일이 준비되면 이 자리에서 바꿔 튼다. */
    fun chime() {
        val track = buildChime()
        track.play()
        handler.postDelayed({ track.release() }, 1_000L)
    }

    /** 안내 문장을 읽고, 다 읽으면(또는 읽을 수 없으면) `onDone`을 부른다. 알림음이 끝난 뒤에 부른다. */
    fun speak(text: String, onDone: () -> Unit) {
        voice.speak(text, onDone)
    }

    /** TTS를 닫는다. 앱을 끝낼 때 한 번 부른다. */
    fun release() {
        end()
        voice.release()
    }

    /**
     * `maxMs`가 지나면 끝까지 들은 것으로 보고 멈춘다. OGG 파일에 `ANDROID_LOOP=true` 태그가 있으면
     * MediaPlayer가 끝없이 되풀이하므로(에뮬레이터 벨소리로 확인), 올린 사람이 넣은 태그가 듣는 차를 붙잡지 않게 한다.
     */
    fun play(file: File, maxMs: Long, onDone: (completed: Boolean, playedMs: Long) -> Unit) {
        releasePlayer()
        this.onDone = onDone
        pausedByFocus = false
        state = "준비 중"
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(file.path)
            isLooping = false
            setOnPreparedListener {
                it.start()
                state = "재생 중"
                limitMs = maxMs
                playedBeforeMs = 0L
                startLimit()
            }
            setOnCompletionListener { finish(completed = true, playedMs = it.duration.toLong()) }
            setOnErrorListener { _, what, extra ->
                EventLog.add("오디오", "재생 오류 ($what/$extra)")
                finish(completed = false, playedMs = 0L)
                true
            }
            prepareAsync()
        }
    }

    /** 재생을 멈추고 실제로 재생한 길이를 돌려준다. `play`에 넘긴 콜백은 부르지 않는다. */
    fun stop(): Long {
        val played = positionMs
        onDone = null
        releasePlayer()
        return played
    }

    /** 안내가 끝나면 부른다. 포커스를 돌려줘서 음악이 다시 나오게 한다. */
    fun end() {
        stop()
        voice.stop()
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
        state = "조용함"
    }

    private fun finish(completed: Boolean, playedMs: Long) {
        val callback = onDone
        onDone = null
        releasePlayer()
        callback?.invoke(completed, playedMs)
    }

    private fun startLimit() {
        playingSinceMs = SystemClock.elapsedRealtime()
        handler.postDelayed(lengthLimit, (limitMs - playedBeforeMs).coerceAtLeast(0L))
    }

    private fun pauseLimit() {
        handler.removeCallbacks(lengthLimit)
        playingSinceMs?.let { playedBeforeMs += SystemClock.elapsedRealtime() - it }
        playingSinceMs = null
    }

    private fun releasePlayer() {
        handler.removeCallbacks(lengthLimit)
        playingSinceMs = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    private fun onFocusChange(change: Int) {
        EventLog.add("오디오", "포커스 ← ${focusChangeName(change)}")
        val current = player
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                voice.interrupt()
                if (current != null && current.isPlaying) {
                    current.pause()
                    pauseLimit()
                    pausedByFocus = true
                    state = "내비 안내로 잠시 멈춤"
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                voice.resume()
                if (current != null && pausedByFocus) {
                    current.start()
                    startLimit()
                    pausedByFocus = false
                    state = "재생 중"
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 다른 앱이 소리를 완전히 가져갔다(예: 운전자가 음악을 새로 틂). 이번 안내는 끝낸다.
                hasFocus = false
                onFocusLost()
            }
        }
    }

    private fun buildChime(): AudioTrack {
        val sampleRate = 22_050
        val notes = listOf(660.0 to 0.15, 0.0 to 0.05, 880.0 to 0.25)
        val pcm = notes.flatMap { (frequency, seconds) ->
            val count = (sampleRate * seconds).toInt()
            List(count) { i ->
                // 앞뒤 10ms를 부드럽게 키우고 줄여서 '틱' 소리를 막는다.
                val fade = min(1.0, min(i, count - i) / (sampleRate * 0.01))
                (sin(2 * PI * frequency * i / sampleRate) * 0.3 * fade * Short.MAX_VALUE).toInt().toShort()
            }
        }.toShortArray()
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
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    companion object {
        private val SPEAKING_USAGES = setOf(
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_ASSISTANT,
        )
    }

    private fun focusChangeName(change: Int) = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "다시 받음(GAIN)"
        AudioManager.AUDIOFOCUS_LOSS -> "잃음(LOSS)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "잠시 잃음(LOSS_TRANSIENT)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "소리 줄여도 됨(CAN_DUCK)"
        else -> "기타($change)"
    }
}
