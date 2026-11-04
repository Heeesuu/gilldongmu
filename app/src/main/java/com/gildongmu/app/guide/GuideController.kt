package com.gildongmu.app.guide

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gildongmu.app.EventLog
import com.gildongmu.app.data.ListenResult
import com.gildongmu.app.data.NearbyMessage
import com.gildongmu.app.data.NearbyQuery
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.Stage
import com.gildongmu.app.data.ThanksResult
import com.gildongmu.app.drive.DriveState
import com.gildongmu.app.drive.Geo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 듣는 흐름 하나를 끝까지 이끈다: 앞쪽 한마디 조회 → "들을까요?" → 1개 재생 → 들은 결과 알리기.
 * 규칙은 docs/api.md 4장을 따르고, 판단은 [GuidePolicy], 소리는 [GuideAudio]에 맡긴다.
 * 모든 메서드는 메인 스레드에서 부른다.
 */
class GuideController(
    private val context: Context,
    private val api: ServerApi,
    private val policy: GuidePolicy = GuidePolicy(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** false인 동안(예: 한마디를 녹음하는 중)에는 "들을까요?"를 묻지 않는다. */
    private val canPrompt: () -> Boolean = { true },
    /** 비속어 필터 설정. 조회할 때마다 읽는다. */
    private val filterOn: () -> Boolean = { true },
) {
    enum class Phase { IDLE, PROMPTING, LOADING, PLAYING }

    /** 안내 카드의 큰 글자 한 줄과 작은 글자 한 줄. 주행 중에 1~2초 안에 읽히도록 짧게 쓴다. */
    data class Status(val title: String, val detail: String)

    private val handler = Handler(Looper.getMainLooper())
    private val audio = GuideAudio(context) { onFocusLost() }
    private val keys = SteeringKeys(context) { onKey(it) }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    var phase = Phase.IDLE
        private set
    /** 지금 묻거나 틀고 있는 한마디. */
    private var current: NearbyMessage? = null
    /** 방금 들은 한마디. 고마워요는 이 한마디에 남긴다. */
    var lastHeard: NearbyMessage? = null
        private set
    var lastThanks: ThanksResult? = null
        private set
    var serverStatus = if (api.configured) "아직 조회 안 함" else "서버 주소가 설정되지 않았어요"
        private set

    private var drive: DriveState? = null
    private var messages: List<NearbyMessage> = emptyList()
    private var approaches: List<Approach> = emptyList()
    private var totalAhead = 0
    private var lastPoll: GuidePolicy.PollMark? = null
    private var nextPollSec = 10
    private var polling = false
    private var lastPromptAtMs: Long? = null
    private var promptDeadlineMs = 0L
    /** 이번 주행에서 이미 물어본 한마디. 대답이 없었거나 넘긴 것도 다시 묻지 않는다. */
    private val asked = mutableSetOf<String>()

    private val promptTimeout = Runnable {
        EventLog.add("안내", "${ANSWER_TIMEOUT_MS / 1_000}초 동안 대답이 없어 넘어가요")
        endSession()
    }

    fun onDrive(state: DriveState) {
        drive = state
        if (api.configured && !polling && policy.shouldPoll(lastPoll, state, clock(), nextPollSec)) poll(state)
        refreshApproaches()
        if (phase == Phase.IDLE && canPrompt()) policy.pickPrompt(approaches, clock(), lastPromptAtMs)?.let { prompt(it) }
    }

    fun onKey(key: SteeringKeys.Key) {
        when (phase) {
            Phase.PROMPTING -> if (key == SteeringKeys.Key.PLAY) accept() else skip()
            Phase.LOADING, Phase.PLAYING -> skip()
            Phase.IDLE -> Unit
        }
    }

    fun accept() {
        val message = current ?: return
        if (phase != Phase.PROMPTING) return
        handler.removeCallbacks(promptTimeout)
        phase = Phase.LOADING
        EventLog.add("안내", "듣기")
        // 음성은 바뀌지 않으므로(Cache-Control) 한 번 받은 파일은 다시 받지 않는다.
        val file = File(context.cacheDir, "messages/${message.id}.ogg")
        if (file.exists()) {
            play(message, file)
            return
        }
        api.audio(message.audioUrl) { result ->
            if (phase != Phase.LOADING || current?.id != message.id) return@audio
            result.onSuccess { bytes ->
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                play(message, file)
            }.onFailure {
                EventLog.add("재생", "음성을 받지 못했어요: ${it.message}")
                endSession()
            }
        }
    }

    /** 묻는 중이면 '안 들을게요'(listen은 보내지 않음), 재생 중이면 중간에 넘기기. */
    fun skip() {
        when (phase) {
            Phase.PROMPTING, Phase.LOADING -> {
                EventLog.add("안내", "넘기기")
                endSession()
            }
            Phase.PLAYING -> {
                val message = current ?: return
                val playedMs = audio.stop()
                report(message, ListenResult.SKIPPED, playedMs)
                lastHeard = message
                lastThanks = null
                endSession()
            }
            Phase.IDLE -> Unit
        }
    }

    fun thanks() {
        val message = lastHeard ?: current?.takeIf { phase == Phase.PLAYING } ?: return
        api.thanks(message.id) { result ->
            result.onSuccess {
                lastThanks = it
                val note = if (it.alreadyThanked) " (이미 남겼어요)" else ""
                EventLog.add("고마워요", "고마워요 ${it.thanksCount}개 · ${it.stage.label}$note")
            }.onFailure { EventLog.add("고마워요", "보내지 못했어요: ${it.message}") }
        }
    }

    /** 설정이 바뀌었을 때처럼, 다음 위치에서 바로 다시 조회하게 한다. */
    fun refreshSoon() {
        lastPoll = null
    }

    /** 화면에 그릴 단계: 고마워요 결과가 있으면 그 단계(꽃이 피는 순간), 아니면 묻거나 들은 한마디의 단계. */
    fun shownStage(): Stage? = lastThanks?.stage ?: (current ?: lastHeard)?.stage

    fun release() {
        handler.removeCallbacks(promptTimeout)
        audio.end()
        keys.release()
        api.shutdown()
    }

    /** 앞쪽 길 한 줄. 자세한 위치는 길 띠가 보여준다. */
    private fun aheadLine(): String {
        if (!api.configured) return serverStatus
        if (drive?.heading == null) return "움직이기 시작하면 앞쪽 길을 살펴요"
        if (approaches.isEmpty()) return "앞쪽 길에 남겨진 한마디가 없어요"
        // 서버가 센 수에서, 조회 뒤에 지나쳤거나 이미 물어본 것만큼 뺀다.
        val count = (totalAhead - (messages.size - approaches.size)).coerceAtLeast(approaches.size)
        return "앞쪽 길에 한마디가 ${count}개 있어요"
    }

    fun status(): Status {
        val message = current
        return when (phase) {
            Phase.IDLE -> idleStatus()
            Phase.PROMPTING -> {
                val left = ((promptDeadlineMs - clock()) / 1_000).coerceAtLeast(0)
                val distance = drive?.let { d -> message?.let { Geo.distanceMeters(d.lat, d.lon, it.lat, it.lon).roundToInt() } }
                Status(
                    "${message?.kind?.label} 한마디가 있어요. 들을까요?",
                    listOfNotNull(distance?.let { "앞쪽 ${it}m" }, message?.stage?.label, "${left}초", "핸들 재생 버튼으로도 들어요")
                        .joinToString(" · "),
                )
            }
            Phase.LOADING -> Status("한마디를 불러오는 중이에요", "")
            Phase.PLAYING -> Status(
                "${message?.kind?.label} 한마디를 듣고 있어요",
                "%.0f / %.0f초 · %s".format(
                    Locale.KOREA, audio.positionMs / 1_000.0, (message?.durationMs ?: 0) / 1_000.0, audio.state,
                ),
            )
        }
    }

    /**
     * 길 띠에 그릴 한마디: 지금 묻거나 틀고 있는 한마디와 앞쪽 후보를 가까운 순으로.
     * 지금 한마디를 막 지나쳤으면 차 바로 앞(0m)에 둔다.
     */
    fun shownAhead(): List<Approach> {
        val d = drive ?: return emptyList()
        val now = current?.let { m ->
            val heading = d.heading
            val passed = heading != null && Geo.angleDiff(Geo.bearing(d.lat, d.lon, m.lat, m.lon), heading) > 90
            Approach(m, if (passed) 0.0 else Geo.distanceMeters(d.lat, d.lon, m.lat, m.lon), etaSec = null)
        }
        return (listOfNotNull(now) + approaches.filter { it.message.id != now?.message?.id }).sortedBy { it.distanceM }
    }

    /** 지금 묻거나 틀고 있는 한마디의 id. 길 띠에서 강조한다. */
    val currentId: String? get() = current?.id

    private fun idleStatus(): Status {
        val wait = lastPromptAtMs?.let { policy.promptIntervalMs - (clock() - it) }?.takeIf { it > 0 }
        val next = if (wait != null) {
            "다음 안내는 %d분 %02d초 뒤부터예요".format(Locale.KOREA, wait / 60_000, wait / 1_000 % 60)
        } else {
            "한마디에 10초 안에 닿으면 알려 드려요"
        }
        val thanks = lastThanks
        return when {
            thanks?.stage == Stage.FLOWER -> Status("꽃이 피었어요!", "이 한마디에 고마워요가 ${thanks.thanksCount}개 모였어요")
            thanks != null -> Status("고마워요를 남겼어요", "지금 ${thanks.thanksCount}개예요 · $next")
            lastHeard != null -> Status("방금 들은 한마디, 좋았나요?", "고마워요를 눌러 주세요 · $next")
            else -> Status(aheadLine(), next)
        }
    }

    private fun poll(state: DriveState) {
        val heading = state.heading ?: return
        polling = true
        lastPoll = GuidePolicy.PollMark(state.lat, state.lon, clock())
        api.nearby(NearbyQuery(state.lat, state.lon, heading, state.speedKmh, filterOn())) { result ->
            polling = false
            result.onSuccess {
                if (it.totalAhead != totalAhead) EventLog.add("서버", "앞쪽 한마디 ${it.totalAhead}개")
                messages = it.messages
                totalAhead = it.totalAhead
                nextPollSec = it.nextPollSec
                serverStatus = "연결됨 · ${timeFormat.format(Date())} 조회"
                refreshApproaches()
            }.onFailure {
                serverStatus = "연결 안 됨 · ${it.message}"
                EventLog.add("서버", "조회 실패: ${it.message}")
            }
        }
    }

    private fun refreshApproaches() {
        approaches = drive?.let { policy.approaches(messages, it, asked) } ?: emptyList()
    }

    private fun prompt(approach: Approach) {
        val message = approach.message
        asked += message.id
        refreshApproaches()
        if (!audio.begin()) {
            EventLog.add("안내", "오디오 포커스를 못 받아 이번 한마디는 건너뛰어요")
            return
        }
        phase = Phase.PROMPTING
        current = message
        lastHeard = null
        lastThanks = null
        lastPromptAtMs = clock()
        promptDeadlineMs = clock() + ANSWER_TIMEOUT_MS
        keys.activate()
        audio.chime()
        handler.postDelayed(promptTimeout, ANSWER_TIMEOUT_MS)
        EventLog.add("안내", "앞쪽 ${approach.distanceM.roundToInt()}m에 ${message.kind.label} 한마디가 있어요. 들을까요?")
    }

    private fun play(message: NearbyMessage, file: File) {
        phase = Phase.PLAYING
        // 올린 길이(최대 15초)보다 1초 넘게 틀지 않는다.
        val maxMs = message.durationMs.toLong().coerceIn(1_000L, MAX_MESSAGE_MS) + 1_000L
        audio.play(file, maxMs) { completed, playedMs ->
            report(message, if (completed) ListenResult.COMPLETED else ListenResult.SKIPPED, playedMs)
            lastHeard = message
            lastThanks = null
            endSession()
        }
    }

    private fun report(message: NearbyMessage, result: ListenResult, playedMs: Long) {
        EventLog.add("재생", "${if (result == ListenResult.COMPLETED) "끝까지 들음" else "중간에 넘김"} (${playedMs}ms)")
        api.listen(message.id, result, playedMs) { sent ->
            sent.onFailure { EventLog.add("서버", "들은 결과를 보내지 못했어요: ${it.message}") }
        }
    }

    private fun onFocusLost() {
        if (phase == Phase.PLAYING) skip() else endSession()
    }

    private fun endSession() {
        handler.removeCallbacks(promptTimeout)
        audio.end()
        keys.deactivate()
        phase = Phase.IDLE
        current = null
    }

    companion object {
        private const val ANSWER_TIMEOUT_MS = 8_000L
        private const val MAX_MESSAGE_MS = 15_000L
    }
}
