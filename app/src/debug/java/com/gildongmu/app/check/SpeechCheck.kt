package com.gildongmu.app.check

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gildongmu.app.EventLog
import com.gildongmu.app.guide.AnswerWords
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 확인 항목: Pleos 음성 인식(SpeechToText SDK, 차 안 엔진)이 한국어 대답을 알아듣는지.
 * 에뮬레이터 마이크는 Mac 소리에 기대므로, 마이크 대신 녹음 파일을 엔진에 바로 넣는다(SDK의 sendAudio, ON_DEVICE 전용).
 * - 파일: 앱 저장소 files/stt/ 안의 .pcm (16kHz·16비트·모노, 머리글 없음). 이름 순서대로 넣는다.
 *   Mac에서 만들기: `say -v Yuna -o 응.wav --file-format=WAVE --data-format=LEI16@16000 "응"` 뒤 WAV 머리글을 떼고,
 *   `adb exec-in run-as com.gildongmu.app --user 10 sh -c 'cat > files/stt/1_eung.pcm' < 1_eung.pcm`로 넣는다.
 * - 파일마다 엔진이 들은 말과 [AnswerWords]로 맞춘 대답(듣기·넘기기·그만·고마워요)을 남긴다.
 * 본 앱의 음성 인식과 엔진 연결이 엇갈리지 않게, 버튼을 누를 때 처음 연결하고 본 앱 화면은 켜지 않은 채로 쓴다.
 */
class SpeechCheck(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private var stt: SpeechToText? = null
    private var ready = false
    private var startWhenReady = false

    private var queue = ArrayDeque<File>()
    private var current: File? = null
    private var heard = ""
    // 콜백이 오는 순서(부분 결과·끝·최종 결과). 엔진은 "끝"을 최종 결과보다 먼저 줄 수 있다.
    private val trace = mutableListOf<String>()
    private var state = "버튼을 누르면 엔진에 연결해요"
    private val results = mutableListOf<String>()
    private val timeout = Runnable { finishCurrent("결과 없음") }
    private val afterEnded = Runnable { finishCurrent("최종 결과 없음") }

    fun summary(): String = (listOf(state) + results).joinToString("\n")

    /** files/stt의 .pcm 파일을 차례로 엔진에 넣는다. 엔진이 준비 전이면 연결부터 한다. */
    fun runFiles() {
        if (current != null || queue.isNotEmpty()) return
        if (!ready) {
            startWhenReady = true
            connect()
            return
        }
        val files = File(context.filesDir, "stt").listFiles { f -> f.extension == "pcm" }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            state = "files/stt에 .pcm 파일이 없어요"
            EventLog.add("음성 확인", state)
            return
        }
        results.clear()
        queue = ArrayDeque(files)
        next()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        sender.shutdownNow()
        stt?.let {
            runCatching {
                it.removeListener(listener)
                it.release()
            }
        }
        stt = null
        ready = false
    }

    private fun connect() {
        if (stt != null) return
        state = "엔진에 연결하는 중"
        val engine = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
        stt = engine
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                handler.post {
                    engine.addListener(listener)
                    engine.setLocale(Locale.KOREA)
                    ready = true
                    state = "엔진 준비됨"
                    EventLog.add("음성 확인", "Pleos 음성 인식 준비됨")
                    if (startWhenReady) {
                        startWhenReady = false
                        runFiles()
                    }
                }
            }
            override fun onFailure(errorType: ErrorType) {
                handler.post {
                    state = "엔진 연결 실패 (${errorType.name})"
                    EventLog.add("음성 확인", state)
                }
            }
        })
    }

    private fun next() {
        val file = queue.removeFirstOrNull()
        if (file == null) {
            state = "모든 파일을 확인했어요"
            return
        }
        val engine = stt ?: return
        current = file
        heard = ""
        trace.clear()
        state = "${file.name} 넣는 중"
        val pcm = file.readBytes()
        // 실제 말하는 속도로 넣는다: 100ms(3,200바이트)씩 100ms마다.
        sender.execute {
            var offset = 0
            while (offset < pcm.size) {
                engine.sendAudio(pcm.copyOfRange(offset, min(offset + CHUNK_BYTES, pcm.size)))
                offset += CHUNK_BYTES
                Thread.sleep(CHUNK_MS)
            }
            engine.completeAudioSend()
        }
        handler.postDelayed(timeout, pcm.size / BYTES_PER_MS + RESULT_WAIT_MS)
    }

    private fun finishCurrent(problem: String?) {
        val file = current ?: return
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(afterEnded)
        current = null
        val answer = AnswerWords.match(heard)?.name ?: "못 맞춤"
        val line = "${file.name} → \"$heard\" → $answer" + (problem?.let { " ($it)" } ?: "") + " · ${trace.joinToString(" → ")}"
        results += line
        EventLog.add("음성 확인", line)
        handler.postDelayed({ next() }, NEXT_DELAY_MS)
    }

    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            handler.post {
                if (current == null) return@post
                trace += if (completed) "최종:$stt" else "부분:$stt"
                if (stt.isNotBlank()) heard = stt
                if (completed) finishCurrent(null)
            }
        }
        override fun onEndedRecognition() {
            handler.post {
                if (current == null) return@post
                trace += "끝"
                // 최종 결과가 바로 뒤따라오므로 조금 기다린다.
                handler.postDelayed(afterEnded, FINAL_WAIT_MS)
            }
        }
        override fun onError(errorType: ErrorType) {
            handler.post {
                trace += "오류:${errorType.name}"
                finishCurrent(errorType.name)
            }
        }
    }

    companion object {
        private const val CHUNK_BYTES = 3_200
        private const val CHUNK_MS = 100L
        private const val BYTES_PER_MS = 32
        private const val RESULT_WAIT_MS = 8_000L
        private const val NEXT_DELAY_MS = 800L
        private const val FINAL_WAIT_MS = 1_000L
    }
}
