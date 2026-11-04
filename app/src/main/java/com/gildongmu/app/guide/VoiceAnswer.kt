package com.gildongmu.app.guide

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.gildongmu.app.EventLog

/**
 * 말로 한 대답을 듣는다. 차에 들어 있는 기본 음성 인식 서비스(안드로이드 SpeechRecognizer)를 쓴다.
 * 마이크는 "들을까요?" 뒤 8초, 한마디를 듣고 난 뒤 몇 초처럼 대답을 기다리는 동안만 연다.
 * 인식은 한 마디가 끝나면 멈추므로, 기다리는 시간이 남아 있으면 다시 시작한다.
 * 인식 서비스가 마이크만 열고 아무 응답도 주지 않으면(Pleos 에뮬레이터의 Gleo AI 서비스에서 확인) 쓸 수 없는 것으로 보고
 * 이번 실행 동안은 버튼으로만 대답받게 한다. 화면이 "말해도 돼요"라고 잘못 안내하지 않게 하려는 것이다.
 * 모든 메서드는 메인 스레드에서 부른다.
 */
class VoiceAnswer(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var untilMs = 0L
    private var onAnswer: ((AnswerWords.Answer, String) -> Boolean)? = null
    /** 부분 결과는 같은 글자로 여러 번 온다. 같은 글자는 한 번만 본다. */
    private var lastText = ""
    // 듣기 한 번이 끝날 때 기록에 남기는 값. 마이크에 소리가 들어오는지, 무엇을 들었는지 확인하는 데 쓴다.
    private var maxRms = Float.NEGATIVE_INFINITY
    private val heard = mutableListOf<String>()
    private val errors = mutableListOf<Int>()
    private var callbacks = 0
    private var startedAtMs = 0L
    private var responsive = true
    private val restart = Runnable { startOnce() }

    /** 이 차에 음성 인식 서비스가 있는가. */
    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context).also {
        if (!it) EventLog.add("목소리", "음성 인식 서비스가 없어 버튼으로만 대답할 수 있어요")
    }

    /** 지금 말로 대답받을 수 있는가: 서비스가 있고, 앞서 들을 때 응답이 있었다. */
    val usable: Boolean get() = available && responsive

    /** 지금 마이크를 열고 듣는 중인가. */
    val listening: Boolean get() = onAnswer != null

    /**
     * `forMs` 동안 듣는다. 알아들은 대답마다 `onAnswer`를 부르고, true를 돌려주면 그만 듣는다.
     * 같은 말을 두 번 처리하지 않도록, 부분 결과와 최종 결과 중 먼저 맞은 것 하나만 넘긴다.
     */
    fun listen(forMs: Long, onAnswer: (AnswerWords.Answer, String) -> Boolean) {
        if (!usable) return
        stop()
        this.onAnswer = onAnswer
        lastText = ""
        maxRms = Float.NEGATIVE_INFINITY
        heard.clear()
        errors.clear()
        callbacks = 0
        startedAtMs = SystemClock.elapsedRealtime()
        EventLog.add("목소리", "대답을 듣고 있어요 (${forMs / 1_000}초)")
        untilMs = SystemClock.elapsedRealtime() + forMs
        startOnce()
    }

    fun stop() {
        handler.removeCallbacks(restart)
        if (onAnswer != null) {
            recognizer?.cancel()
            summarize()
        }
        onAnswer = null
    }

    private fun summarize() {
        val level = if (maxRms.isInfinite()) "없음" else "%.1f".format(maxRms)
        EventLog.add("목소리", "듣기 끝 · 가장 큰 소리 $level · 들은 말 ${heard.joinToString().ifEmpty { "없음" }} · 오류 ${errors.joinToString().ifEmpty { "없음" }}")
        if (callbacks == 0 && SystemClock.elapsedRealtime() - startedAtMs >= SILENT_SERVICE_MS) {
            responsive = false
            EventLog.add("목소리", "음성 인식 서비스가 응답하지 않아 이번에는 버튼으로만 대답받아요")
        }
    }

    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun startOnce() {
        if (onAnswer == null) return
        if (SystemClock.elapsedRealtime() >= untilMs) {
            summarize()
            onAnswer = null
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    private fun handle(results: Bundle?): Boolean {
        val callback = onAnswer ?: return true
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        texts.firstOrNull()?.takeIf { it.isNotBlank() && it !in heard }?.let { heard += it }
        for (text in texts) {
            val answer = AnswerWords.match(text) ?: continue
            if (text == lastText) return false
            lastText = text
            EventLog.add("목소리", "알아들은 말: \"$text\" → ${answer.name}")
            if (callback(answer, text)) {
                stop()
                return true
            }
            return false
        }
        return false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callbacks++
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            callbacks++
            if (rmsdB > maxRms) maxRms = rmsdB
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            callbacks++
            handle(partialResults)
        }

        override fun onResults(results: Bundle?) {
            callbacks++
            if (!handle(results)) handler.postDelayed(restart, RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            if (onAnswer == null) return
            callbacks++
            errors += error
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    EventLog.add("목소리", "마이크 권한이 없어 말로 대답할 수 없어요")
                    onAnswer = null
                }
                // 말이 없었거나 못 알아들었으면 남은 시간 동안 다시 듣는다.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> handler.postDelayed(restart, RESTART_DELAY_MS)
                else -> {
                    EventLog.add("목소리", "음성 인식 오류($error)")
                    handler.postDelayed(restart, RESTART_DELAY_MS * 3)
                }
            }
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 200L
        /** 이만큼 듣는 동안 서비스가 한 번도 응답하지 않으면(준비됨·소리 크기조차) 쓸 수 없는 서비스로 본다. */
        private const val SILENT_SERVICE_MS = 3_000L
    }
}
