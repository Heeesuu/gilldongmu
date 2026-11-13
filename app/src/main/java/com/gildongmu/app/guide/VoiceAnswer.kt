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
import java.util.concurrent.Executors
import kotlin.math.min
import ai.pleos.playground.stt.SpeechToText as PleosStt

/**
 * 말로 한 대답을 듣고, 남긴 한마디를 검수용 글자로 바꾼다([transcribe]). 둘 다 같은 Pleos 엔진을 쓴다.
 * 말로 한 대답을 듣는 방식:
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
        // 한마디를 글자로 바꾸는 동안은 엔진을 쓰고 있어 대답을 듣지 않는다(버튼으로는 대답할 수 있다).
        if (transcription != null) return
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
        // 바꾸던 글자는 지금까지 들은 만큼 넘기고, 기다리던 것은 빈 글자로 넘긴다(앱이 닫혀도 올리기는 이어지게).
        transcription?.finish("앱 종료")
        while (queued.isNotEmpty()) queued.removeFirst().second("")
        sender.shutdown()
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
                transcription?.let {
                    if (completed) it.onFinal(stt) else it.onPartial(stt)
                    return@post
                }
                if (onAnswer == null) return@post
                callbacks++
                handle(listOf(stt))
            }
        }
        override fun onEndedRecognition() {
            handler.post {
                transcription?.let {
                    it.onEnded()
                    return@post
                }
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
        transcription?.let {
            it.onError(errorType.name)
            return
        }
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

    // ---- 남긴 한마디를 글자로 바꾸기 (검수용) ----

    private var transcription: Transcription? = null
    private val queued = ArrayDeque<Pair<ByteArray, (String) -> Unit>>()
    private val sender = Executors.newSingleThreadExecutor()

    /**
     * 녹음한 한마디(16kHz·16비트·모노 PCM, [com.gildongmu.app.record.OpusPcm])를 차 안 엔진(ON_DEVICE)으로 글자로 바꾼다.
     * 서버는 이 글자로 검수한다. 차 마이크 음성을 외부 음성 인식 서비스로 보내지 않기 위해서다(공모전 개발 범위 안내).
     * - 소리는 마이크 대신 SDK의 sendAudio로 실제 속도대로 넣는다(개발용 확인 화면 ⑦과 같은 방식).
     * - 엔진은 말이 잠깐 끊기면 그 자리에서 인식을 마친다. 넣을 소리가 남았으면 바로 다시 시작해 글자를 이어 붙인다.
     * - 대답을 듣는 중이면 먼저 멈춘다. 이미 바꾸는 중이면 끝난 뒤 차례로 바꾼다.
     * - Pleos 엔진이 없거나 소리가 비었으면 빈 글자를 넘긴다(서버는 글자가 없으면 공개하지 않고 보류한다).
     * `done`은 메인 스레드에서 한 번 부른다.
     */
    fun transcribe(pcm: ByteArray, done: (String) -> Unit) {
        val engine = pleos
        if (!pleosReady || engine == null || pcm.isEmpty()) {
            EventLog.add("목소리", "한마디를 글자로 바꾸지 못했어요 (${if (pcm.isEmpty()) "소리 없음" else "Pleos 음성 인식 없음"})")
            done("")
            return
        }
        if (transcription != null) {
            queued.addLast(pcm to done)
            return
        }
        stop()
        val t = Transcription(engine, pcm, done)
        transcription = t
        handler.postDelayed(t.giveUp, pcm.size / BYTES_PER_MS + TRANSCRIBE_EXTRA_MS)
        t.request()
        sender.execute { t.send() }
    }

    private inner class Transcription(val engine: PleosStt, val pcm: ByteArray, val done: (String) -> Unit) {
        private val texts = mutableListOf<String>()
        private var partial = ""
        private var requests = 0
        private var finished = false

        // 보내는 스레드와 함께 보는 값
        @Volatile var listening = false
        @Volatile var sentAll = false
        @Volatile var stopped = false

        val giveUp = Runnable { finish("시간 초과") }
        // 엔진은 "끝"을 최종 결과보다 먼저 알린다. 최종 결과가 끝내 안 오면 마지막 부분 결과를 쓴다.
        private val afterEnded = Runnable { onFinal(partial) }
        private val afterSent = Runnable { onFinal(partial) }

        fun request() {
            requests++
            listening = runCatching { engine.request() }.isSuccess
            if (!listening) finish("요청 실패")
        }

        /** 보내는 스레드: 엔진이 듣고 있을 때만 0.1초 분량씩 실제 속도로 넣는다. */
        fun send() {
            var offset = 0
            var idleMs = 0L
            while (offset < pcm.size && !stopped) {
                if (!listening) {
                    Thread.sleep(IDLE_POLL_MS)
                    idleMs += IDLE_POLL_MS
                    if (idleMs > MAX_IDLE_MS) break
                    continue
                }
                idleMs = 0
                val end = min(offset + CHUNK_BYTES, pcm.size)
                runCatching { engine.sendAudio(pcm.copyOfRange(offset, end)) }
                offset = end
                Thread.sleep(CHUNK_MS)
            }
            sentAll = true
            if (stopped) return
            runCatching { engine.completeAudioSend() }
            handler.post {
                if (!listening) finish(null) else handler.postDelayed(afterSent, RESULT_WAIT_MS)
            }
        }

        fun onPartial(text: String) {
            partial = text.trim()
        }

        fun onEnded() {
            handler.removeCallbacks(afterEnded)
            handler.postDelayed(afterEnded, FINAL_WAIT_MS)
        }

        fun onError(name: String) {
            // 말이 없는 구간(TIMEOUT 등)이면 그 구간은 건너뛰고 남은 소리를 이어서 넣는다.
            onFinal(partial, problem = name)
        }

        fun onFinal(text: String, problem: String? = null) {
            if (finished || !listening) return
            handler.removeCallbacks(afterEnded)
            handler.removeCallbacks(afterSent)
            listening = false
            text.trim().takeIf { it.isNotEmpty() }?.let { texts += it }
            partial = ""
            when {
                sentAll -> finish(problem)
                requests >= MAX_REQUESTS -> finish("다시 시작 횟수 초과")
                else -> request()
            }
        }

        fun finish(problem: String?) {
            if (finished) return
            finished = true
            stopped = true
            listening = false
            handler.removeCallbacks(giveUp)
            handler.removeCallbacks(afterEnded)
            handler.removeCallbacks(afterSent)
            runCatching { engine.stop() }
            if (partial.isNotEmpty()) texts += partial
            val text = texts.joinToString(" ").trim()
            transcription = null
            EventLog.add("목소리", "한마디를 글자로 바꿨어요 (${text.length}자, 인식 ${requests}번${problem?.let { " · $it" } ?: ""})")
            done(text)
            queued.removeFirstOrNull()?.let { (next, nextDone) -> transcribe(next, nextDone) }
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 200L
        /** 이만큼 듣는 동안 엔진이 한 번도 응답하지 않으면(준비됨·소리 크기조차) 쓸 수 없는 엔진으로 본다. */
        private const val SILENT_SERVICE_MS = 3_000L

        // 한마디 글자로 바꾸기: 16kHz·16비트·모노에서 0.1초 = 3,200바이트
        private const val CHUNK_BYTES = 3_200
        private const val CHUNK_MS = 100L
        private const val BYTES_PER_MS = 32
        private const val IDLE_POLL_MS = 20L
        private const val MAX_IDLE_MS = 3_000L
        private const val FINAL_WAIT_MS = 1_500L
        private const val RESULT_WAIT_MS = 5_000L
        private const val TRANSCRIBE_EXTRA_MS = 20_000L
        private const val MAX_REQUESTS = 12
    }
}
