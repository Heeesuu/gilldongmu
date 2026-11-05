package com.gildongmu.app.guide

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import com.gildongmu.app.EventLog
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * "들어 볼까요?" 문장을 읽는다. 읽을 수 없으면 바로 끝난 것으로 본다(그러면 알림음만 난 셈이다).
 * - 먼저 Pleos TextToSpeech SDK(차 안 엔진, ON_DEVICE)로 읽는다. 앱 등록(자격 증명)이 필요 없는 모드다.
 * - SDK를 쓸 수 없는 기기(일반 안드로이드)에서는 안드로이드 TTS로 읽는다.
 * - 내비 안내가 끼어들면 [interrupt]로 멈추고, 안내가 끝나면 [resume]으로 처음부터 다시 읽는다.
 * 소리는 한마디와 같은 미디어 용도로 낸다. 어시스턴트 용도는 내비 안내와 부딪힌다 (docs/api.md 4장).
 * 메인 스레드에서 부른다. 엔진의 콜백은 메인 스레드로 옮겨서 처리한다.
 */
class PromptVoice(context: Context, private val attributes: AudioAttributes) {

    private enum class State { IDLE, WAITING, SPEAKING, INTERRUPTED }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var pleos: PleosTts? = null
    private var pleosReady = false
    private var android: AndroidTts? = null
    private var androidReady = false

    private var state = State.IDLE
    private var text = ""
    private var onDone: (() -> Unit)? = null
    // 우리가 멈춘 것에 대한 onStop은 "다 읽음"으로 보지 않는다.
    private var stopRequested = false

    // 엔진이 대답을 늦게 주거나 끝내 안 주면 오래 붙잡지 않는다. 시작 전과 읽는 중을 따로 잰다.
    private val startTimeout = Runnable {
        EventLog.add("오디오", "문장 읽기가 시작되지 않아 넘어가요")
        finish()
    }
    private val speechTimeout = Runnable {
        EventLog.add("오디오", "문장 읽기가 끝났다는 알림이 없어 넘어가요")
        finish()
    }

    /** 지금 읽고 있거나 읽기를 기다리는 중인가. 내비 안내로 멈춘 동안도 포함한다. */
    val busy: Boolean
        get() = state != State.IDLE

    init {
        startPleos()
    }

    /** 문장을 읽고, 다 읽으면(또는 읽을 수 없으면) `onDone`을 부른다. 앞서 읽던 문장은 콜백 없이 멈춘다. */
    fun speak(text: String, onDone: () -> Unit) {
        stop()
        this.text = text
        this.onDone = onDone
        start()
    }

    /** 내비 안내가 끼어들 때: 읽기를 멈추되 문장과 콜백은 남겨 둔다. */
    fun interrupt() {
        if (state != State.WAITING && state != State.SPEAKING) return
        EventLog.add("오디오", "내비 안내가 끝나면 문장을 다시 읽어요")
        clearTimeouts()
        state = State.INTERRUPTED
        stopEngine()
    }

    /** 내비 안내가 끝났을 때: 멈췄던 문장을 처음부터 다시 읽는다. */
    fun resume() {
        if (state != State.INTERRUPTED) return
        start()
    }

    /** 읽기를 멈춘다. `speak`에 넘긴 콜백은 부르지 않는다. */
    fun stop() {
        val wasBusy = busy
        clearTimeouts()
        state = State.IDLE
        onDone = null
        if (wasBusy) stopEngine()
    }

    /** 엔진을 닫는다. 앱을 끝낼 때 한 번 부른다. */
    fun release() {
        stop()
        pleos?.let {
            runCatching {
                it.removeEventListener(pleosListener)
                it.release()
            }
        }
        pleos = null
        pleosReady = false
        android?.shutdown()
        android = null
        androidReady = false
    }

    private fun start() {
        if (!pleosReady && !androidReady) {
            finish()
            return
        }
        state = State.WAITING
        stopRequested = false
        val requested = if (pleosReady) {
            runCatching { pleos?.speak(text, true) }.isSuccess
        } else {
            android?.speak(text, AndroidTts.QUEUE_FLUSH, null, UTTERANCE_ID) == AndroidTts.SUCCESS
        }
        if (!requested) {
            EventLog.add("오디오", "문장 읽기를 요청하지 못했어요")
            finish()
            return
        }
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
    }

    private fun onEngineStart() {
        if (state != State.WAITING) return
        handler.removeCallbacks(startTimeout)
        state = State.SPEAKING
        stopRequested = false
        handler.postDelayed(speechTimeout, SPEECH_TIMEOUT_MS)
    }

    private fun onEngineDone() {
        if (state == State.WAITING || state == State.SPEAKING) finish()
    }

    private fun onEngineStop() {
        // 다른 앱(예: 음성 비서)이 멈췄다면 다 읽은 것으로 보고 대답을 기다린다.
        if (!stopRequested && (state == State.WAITING || state == State.SPEAKING)) finish()
    }

    private fun onEngineError(reason: String) {
        if (state != State.WAITING && state != State.SPEAKING) return
        EventLog.add("오디오", "문장을 읽지 못했어요 ($reason)")
        finish()
    }

    private fun finish() {
        clearTimeouts()
        val callback = onDone
        onDone = null
        state = State.IDLE
        callback?.invoke()
    }

    private fun stopEngine() {
        stopRequested = true
        if (pleosReady) runCatching { pleos?.stop() }
        if (androidReady) android?.stop()
    }

    private fun clearTimeouts() {
        handler.removeCallbacks(startTimeout)
        handler.removeCallbacks(speechTimeout)
    }

    // ---- Pleos TextToSpeech SDK ----

    private val pleosListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() {
            handler.post { onEngineStart() }
        }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() {
            handler.post { onEngineDone() }
        }
        override fun onStop() {
            handler.post { onEngineStop() }
        }
        override fun onError(errorType: ErrorType) {
            handler.post { onEngineError("Pleos ${errorType.name}") }
        }
    }

    private fun startPleos() {
        val tts = try {
            PleosTts(appContext, Mode.ON_DEVICE)
        } catch (e: RuntimeException) {
            EventLog.add("오디오", "Pleos TTS를 만들지 못해 안드로이드 TTS로 읽어요: ${e.message}")
            startAndroid()
            return
        }
        pleos = tts
        val result = runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post { onPleosReady(tts) }
                }
                override fun onFailure(errorType: ErrorType) {
                    handler.post { onPleosFailed(tts, errorType.name) }
                }
            })
        }
        result.exceptionOrNull()?.let { onPleosFailed(tts, it.message ?: it.javaClass.simpleName) }
    }

    private fun onPleosReady(tts: PleosTts) {
        if (pleos !== tts) return
        val result = runCatching {
            tts.addEventListener(pleosListener)
            tts.setLocale(Locale.KOREA)
            tts.setAudioAttributes(attributes)
        }
        if (result.isFailure) {
            onPleosFailed(tts, result.exceptionOrNull()?.message ?: "설정 실패")
            return
        }
        pleosReady = true
        EventLog.add("오디오", "Pleos TTS(차 안 엔진)로 문장을 읽어요")
    }

    private fun onPleosFailed(tts: PleosTts, reason: String) {
        if (pleos !== tts) return
        EventLog.add("오디오", "Pleos TTS를 쓸 수 없어 안드로이드 TTS로 읽어요 ($reason)")
        runCatching { tts.release() }
        pleos = null
        pleosReady = false
        startAndroid()
    }

    // ---- 안드로이드 TTS (Pleos가 아닌 기기) ----

    private fun startAndroid() {
        if (android != null) return
        android = AndroidTts(appContext) { status -> handler.post { onAndroidReady(status) } }
    }

    private fun onAndroidReady(status: Int) {
        val tts = android ?: return
        val language = if (status == AndroidTts.SUCCESS) tts.setLanguage(Locale.KOREAN) else AndroidTts.LANG_NOT_SUPPORTED
        androidReady = language >= AndroidTts.LANG_AVAILABLE
        if (!androidReady) {
            EventLog.add("오디오", "TTS로 한국어를 읽을 수 없어 알림음만 내요 ($status/$language)")
            return
        }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                handler.post { onEngineStart() }
            }
            override fun onDone(utteranceId: String?) {
                handler.post { onEngineDone() }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                handler.post { onEngineStop() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { onEngineError("안드로이드 TTS") }
            }
        })
        EventLog.add("오디오", "안드로이드 TTS로 문장을 읽어요")
    }

    companion object {
        private const val UTTERANCE_ID = "gildongmu-prompt"
        private const val START_TIMEOUT_MS = 3_000L
        // 가장 긴 문장("꽃을 든 길동무가 … 들어 볼까요?")도 6초 안쪽이다. 넉넉하게 잡는다.
        private const val SPEECH_TIMEOUT_MS = 10_000L
    }
}
