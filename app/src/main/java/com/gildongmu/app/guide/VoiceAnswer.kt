package com.gildongmu.app.guide

import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
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
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText as PleosStt

/**
 * 말로 한 대답을 듣는다.
 * - 먼저 Pleos SpeechToText SDK(차 안 엔진, ON_DEVICE)로 듣는다. 앱 등록(자격 증명)이 필요 없는 모드다.
 * - SDK를 쓸 수 없는 기기(일반 안드로이드)에서는 기본 음성 인식 서비스(SpeechRecognizer)로 듣는다.
 * 마이크는 "들을까요?" 뒤 8초, 한마디를 듣고 난 뒤 몇 초처럼 대답을 기다리는 동안만 연다.
 * 인식은 한 마디가 끝나면 멈추므로, 기다리는 시간이 남아 있으면 다시 시작한다.
 * 인식 엔진이 마이크만 열고 아무 응답도 주지 않으면(Pleos 에뮬레이터의 SpeechRecognizer에서 확인) 쓸 수 없는 것으로 보고
 * 이번 실행 동안은 버튼으로만 대답받게 한다. 화면이 "말해도 돼요"라고 잘못 안내하지 않게 하려는 것이다.
 * 모든 메서드는 메인 스레드에서 부른다. 엔진의 콜백은 메인 스레드로 옮겨서 처리한다.
 */
class VoiceAnswer(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var pleos: PleosStt? = null
    private var pleosReady = false
    // 우리가 request()로 시작한 인식이 아직 끝나지 않았는가. 우리가 멈춘 인식의 끝 알림은 무시한다.
    private var pleosRunning = false
    private var androidAvailable = false
    private var recognizer: SpeechRecognizer? = null

    private var untilMs = 0L
    private var onAnswer: ((AnswerWords.Answer, String) -> Boolean)? = null
    /** 부분 결과는 같은 글자로 여러 번 온다. 같은 글자는 한 번만 본다. */
    private var lastText = ""
    // 듣기 한 번이 끝날 때 기록에 남기는 값. 마이크에 소리가 들어오는지, 무엇을 들었는지 확인하는 데 쓴다.
    private var maxRms = Double.NEGATIVE_INFINITY
    private val heard = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private var callbacks = 0
    private var startedAtMs = 0L
    private var responsive = true
    private val restart = Runnable { startOnce() }

    init {
        startPleos()
    }

    /** 이 차에서 쓸 수 있는 음성 인식 엔진이 있는가. Pleos 엔진은 앱을 켜고 조금 뒤에 준비된다. */
    val available: Boolean get() = pleosReady || androidAvailable

    /** 지금 말로 대답받을 수 있는가: 엔진이 있고, 앞서 들을 때 응답이 있었다. */
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
        maxRms = Double.NEGATIVE_INFINITY
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
            if (pleosReady) {
                pleosRunning = false
                runCatching { pleos?.stop() }
            } else {
                recognizer?.cancel()
            }
            summarize()
        }
        onAnswer = null
    }

    fun release() {
        stop()
        pleos?.let {
            runCatching {
                it.removeListener(pleosListener)
                it.release()
            }
        }
        pleos = null
        pleosReady = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun summarize() {
        val level = if (maxRms.isInfinite()) "없음" else "%.1f".format(Locale.KOREA, maxRms)
        EventLog.add("목소리", "듣기 끝 · 가장 큰 소리 $level · 들은 말 ${heard.joinToString().ifEmpty { "없음" }} · 오류 ${errors.joinToString().ifEmpty { "없음" }}")
        if (callbacks == 0 && SystemClock.elapsedRealtime() - startedAtMs >= SILENT_SERVICE_MS) {
            responsive = false
            EventLog.add("목소리", "음성 인식 서비스가 응답하지 않아 이번에는 버튼으로만 대답받아요")
        }
    }

    private fun startOnce() {
        if (onAnswer == null) return
        if (SystemClock.elapsedRealtime() >= untilMs) {
            summarize()
            onAnswer = null
            return
        }
        if (pleosReady) startPleosOnce() else startAndroidOnce()
    }

    private fun restartLater(delayMs: Long) {
        if (onAnswer == null) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
    }

    /** 알아들은 글자(첫째가 가장 그럴듯한 것)를 대답 낱말과 맞춰 본다. 그만 들어야 하면 true. */
    private fun handle(texts: List<String>): Boolean {
        val callback = onAnswer ?: return true
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

    // ---- Pleos SpeechToText SDK ----

    private fun startPleos() {
        val stt = try {
            PleosStt(appContext, Mode.ON_DEVICE)
        } catch (e: RuntimeException) {
            onPleosUnavailable(e.message ?: e.javaClass.simpleName)
            return
        }
        pleos = stt
        val result = runCatching {
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post { onPleosReady(stt) }
                }
                override fun onFailure(errorType: ErrorType) {
                    handler.post { onPleosFailed(stt, errorType.name) }
                }
            })
        }
        result.exceptionOrNull()?.let { onPleosFailed(stt, it.message ?: it.javaClass.simpleName) }
    }

    private fun onPleosReady(stt: PleosStt) {
        if (pleos !== stt) return
        val result = runCatching {
            stt.addListener(pleosListener)
            stt.setLocale(Locale.KOREA)
        }
        if (result.isFailure) {
            onPleosFailed(stt, result.exceptionOrNull()?.message ?: "설정 실패")
            return
        }
        pleosReady = true
        EventLog.add("목소리", "Pleos 음성 인식(차 안 엔진)으로 대답을 들어요")
    }

    private fun onPleosFailed(stt: PleosStt, reason: String) {
        if (pleos !== stt) return
        runCatching { stt.release() }
        pleos = null
        pleosReady = false
        onPleosUnavailable(reason)
    }

    private fun onPleosUnavailable(reason: String) {
        androidAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        EventLog.add(
            "목소리",
            if (androidAvailable) "Pleos 음성 인식을 쓸 수 없어 기본 음성 인식으로 들어요 ($reason)"
            else "음성 인식 서비스가 없어 버튼으로만 대답할 수 있어요 ($reason)",
        )
    }

    private fun startPleosOnce() {
        pleosRunning = true
        if (runCatching { pleos?.request() }.isFailure) {
            pleosRunning = false
            errors += "요청 실패"
            restartLater(RESTART_DELAY_MS * 3)
        }
    }

    private val pleosListener = object : ResultListener {
        override fun onReady() {
            handler.post { callbacks++ }
        }
        override fun onStartedRecognition() {
            handler.post { callbacks++ }
        }
        override fun onUpdatedRms(rms: Double) {
            handler.post {
                callbacks++
                if (rms > maxRms) maxRms = rms
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            handler.post {
                if (onAnswer == null) return@post
                callbacks++
                handle(listOf(stt))
            }
        }
        override fun onEndedRecognition() {
            handler.post {
                if (!pleosRunning) return@post
                pleosRunning = false
                callbacks++
                restartLater(RESTART_DELAY_MS)
            }
        }
        override fun onError(errorType: ErrorType) {
            handler.post { onPleosError(errorType) }
        }
    }

    private fun onPleosError(errorType: ErrorType) {
        if (onAnswer == null) return
        pleosRunning = false
        callbacks++
        errors += errorType.name
        when (errorType) {
            ErrorType.PERMISSION_DENIED, ErrorType.MIC_ERROR -> {
                EventLog.add("목소리", "마이크를 쓸 수 없어 말로 대답할 수 없어요 (${errorType.name})")
                summarize()
                onAnswer = null
            }
            // 말이 없었거나 못 알아들었으면 남은 시간 동안 다시 듣는다.
            ErrorType.TIMEOUT -> restartLater(RESTART_DELAY_MS)
            else -> {
                EventLog.add("목소리", "음성 인식 오류(${errorType.name})")
                restartLater(RESTART_DELAY_MS * 3)
            }
        }
    }

    // ---- 안드로이드 SpeechRecognizer (Pleos가 아닌 기기) ----

    private fun startAndroidOnce() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(androidListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        r.startListening(intent)
    }

    private fun texts(results: Bundle?): List<String> =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    private val androidListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callbacks++
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            callbacks++
            if (rmsdB > maxRms) maxRms = rmsdB.toDouble()
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            callbacks++
            handle(texts(partialResults))
        }

        override fun onResults(results: Bundle?) {
            callbacks++
            if (!handle(texts(results))) restartLater(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            if (onAnswer == null) return
            callbacks++
            errors += error.toString()
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    EventLog.add("목소리", "마이크 권한이 없어 말로 대답할 수 없어요")
                    onAnswer = null
                }
                // 말이 없었거나 못 알아들었으면 남은 시간 동안 다시 듣는다.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartLater(RESTART_DELAY_MS)
                else -> {
                    EventLog.add("목소리", "음성 인식 오류($error)")
                    restartLater(RESTART_DELAY_MS * 3)
                }
            }
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 200L
        /** 이만큼 듣는 동안 엔진이 한 번도 응답하지 않으면(준비됨·소리 크기조차) 쓸 수 없는 엔진으로 본다. */
        private const val SILENT_SERVICE_MS = 3_000L
    }
}
