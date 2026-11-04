package com.gildongmu.app.record

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import com.gildongmu.app.EventLog
import java.io.File

/**
 * 한마디 녹음 (docs/api.md 3.2): OGG 컨테이너 + Opus, 16kHz 모노, 약 24kbps, 최대 15초.
 * 녹음하는 동안 차 스피커의 음악이 섞여 들어가지 않도록 잠깐 포커스(GAIN_TRANSIENT)로 음악을 멈춘다.
 * 내비 안내는 막지 않는다(EXCLUSIVE를 쓰지 않음).
 */
class VoiceRecorder(private val context: Context, private val onFinished: (Recording?) -> Unit) {

    class Recording(val file: File, val durationMs: Long)

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMs = 0L

    val isRecording get() = recorder != null

    val elapsedMs: Long
        get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAtMs

    /** 마이크를 열지 못하면 false. */
    fun start(): Boolean {
        if (recorder != null) return false
        val file = File(context.cacheDir, "recordings/${System.currentTimeMillis()}.ogg").apply { parentFile?.mkdirs() }
        audioManager.requestAudioFocus(focusRequest)
        val r = MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(MAX_DURATION_MS.toInt())
            r.setOutputFile(file)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    EventLog.add("녹음", "15초가 되어 자동으로 끝냈어요")
                    stop()
                }
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            audioManager.abandonAudioFocusRequest(focusRequest)
            EventLog.add("녹음", "마이크를 열지 못했어요: ${e.javaClass.simpleName} ${e.message}")
            return false
        }
        recorder = r
        output = file
        startedAtMs = SystemClock.elapsedRealtime()
        EventLog.add("녹음", "시작")
        return true
    }

    /** 녹음을 끝내고 [onFinished]로 알린다. 1초보다 짧거나 저장에 실패하면 버리고 null을 넘긴다. */
    fun stop() {
        val r = recorder ?: return
        val file = output
        val durationMs = elapsedMs.coerceAtMost(MAX_DURATION_MS)
        recorder = null
        output = null
        // 녹음된 내용이 거의 없으면 stop()이 예외를 던진다.
        val saved = runCatching { r.stop() }.isSuccess
        r.release()
        audioManager.abandonAudioFocusRequest(focusRequest)

        if (file == null || !saved || durationMs < MIN_DURATION_MS) {
            file?.delete()
            EventLog.add("녹음", "너무 짧아서 남기지 않았어요 (${durationMs}ms)")
            onFinished(null)
            return
        }
        EventLog.add("녹음", "끝 · ${durationMs}ms · ${file.length() / 1024}KB")
        onFinished(Recording(file, durationMs))
    }

    /** 화면을 닫을 때처럼 결과 없이 버린다. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        output?.delete()
        output = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    companion object {
        const val MAX_DURATION_MS = 15_000L
        private const val MIN_DURATION_MS = 1_000L
    }
}
