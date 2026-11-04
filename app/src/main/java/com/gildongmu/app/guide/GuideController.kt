package com.gildongmu.app.guide

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gildongmu.app.EventLog
import com.gildongmu.app.data.Kind
import com.gildongmu.app.data.ListenResult
import com.gildongmu.app.data.NearbyMessage
import com.gildongmu.app.data.NearbyQuery
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.Stage
import com.gildongmu.app.data.ThanksResult
import com.gildongmu.app.drive.DriveState
import com.gildongmu.app.drive.Geo
import com.gildongmu.app.guide.AnswerWords.Answer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 듣는 흐름 하나를 끝까지 이끈다: 앞쪽 한마디 조회 → "들어 볼까요?" → 묶음 차례로 재생 → 들은 결과 알리기.
 * 안내 문장은 "길동무가 그 자리에 남긴 말"이라는 말투로 쓴다 (디자인 "길동무").
 * 가까이 모인 한마디는 한 번에 묻고(GuidePolicy.pickPrompt), 대답은 버튼·핸들 버튼·말로 받는다.
 * 규칙은 docs/api.md 4장을 따르고, 판단은 [GuidePolicy], 소리는 [GuideAudio], 말은 [VoiceAnswer]에 맡긴다.
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
    /** 말로 대답하기 설정과 마이크 권한. 물을 때마다 읽는다. */
    private val voiceOn: () -> Boolean = { false },
    /** 내 닉네임(내 기기에서만 보임). 조용할 때 인사에 쓴다. */
    private val nickname: () -> String? = { null },
) {
    /** AFTER: 한마디 하나를 듣고 난 뒤 "고마워"를 기다리는 몇 초. */
    enum class Phase { IDLE, PROMPTING, LOADING, PLAYING, AFTER }

    /** 안내 카드의 큰 글자 한 줄과 작은 글자 한 줄. 주행 중에 1~2초 안에 읽히도록 짧게 쓴다. */
    data class Status(val title: String, val detail: String)

    /** 안내 카드에 세울 길동무 한 명: 한마디 id(몸 색을 정함)와 단계(모습을 정함). */
    data class Shown(val id: String, val stage: Stage)

    /** 이번 길(앱을 켠 뒤)의 기록: 앞쪽에서 만난 길동무, 끝까지 들은 한마디, 내가 전한 고마워요. */
    data class Trip(val met: Int, val heard: Int, val thanked: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val audio = GuideAudio(context) { onFocusLost() }
    private val keys = SteeringKeys(context) { onKey(it) }
    private val voice = VoiceAnswer(context)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    var phase = Phase.IDLE
        private set
    /** 이번에 묻거나 들려주는 한마디 묶음(닿는 순서)과 지금 차례. */
    private var queue: List<NearbyMessage> = emptyList()
    private var index = 0
    private val current: NearbyMessage? get() = queue.getOrNull(index)
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
    /** 대답을 기다리는 마감 시각. 안내 문장을 다 읽은 뒤부터 센다. 0이면 아직 읽는 중이다. */
    private var deadlineMs = 0L
    /** 이번 주행에서 이미 물어본 한마디. 대답이 없었거나 넘긴 것도 다시 묻지 않는다. */
    private val asked = mutableSetOf<String>()
    private val met = mutableSetOf<String>()
    private var heardCount = 0
    private var thankedCount = 0

    private val promptTimeout = Runnable {
        EventLog.add("안내", "${ANSWER_TIMEOUT_MS / 1_000}초 동안 대답이 없어 넘어가요")
        endSession()
    }
    private val afterTimeout = Runnable { next() }

    /** 이번 안내에서 말로 대답할 수 있는가. 화면 문구에 쓴다. */
    val voiceReady: Boolean get() = voice.usable && voiceOn()

    /** 고마워요를 남길 한마디가 있는가: 듣고 있거나 방금 들었다. */
    val canThank: Boolean get() = phase == Phase.PLAYING || phase == Phase.AFTER || (phase == Phase.IDLE && lastHeard != null)

    /** 묶음에 아직 들을 한마디가 남았는가. "다음" 버튼에 쓴다. */
    val hasNext: Boolean get() = phase != Phase.IDLE && phase != Phase.PROMPTING && index + 1 < queue.size

    fun onDrive(state: DriveState) {
        drive = state
        if (api.configured && !polling && policy.shouldPoll(lastPoll, state, clock(), nextPollSec)) poll(state)
        refreshApproaches()
        if (phase == Phase.IDLE && canPrompt()) {
            policy.pickPrompt(approaches, clock(), lastPromptAtMs).takeIf { it.isNotEmpty() }?.let { prompt(it) }
        }
    }

    /** 묻는 중: 재생 버튼은 듣기, 다음 곡 버튼은 넘기기. 듣는 중: 다음 곡 버튼은 다음 한마디, 재생 버튼은 그만 듣기. */
    fun onKey(key: SteeringKeys.Key) {
        when (phase) {
            Phase.PROMPTING -> if (key == SteeringKeys.Key.PLAY) accept() else skip()
            Phase.LOADING, Phase.PLAYING, Phase.AFTER -> if (key == SteeringKeys.Key.NEXT) skip() else stop()
            Phase.IDLE -> Unit
        }
    }

    /**
     * Gleo로 "길동무 들려 줘"라고 했을 때: 묻는 중이면 듣기, 조용할 때는 가장 가까운 묶음을 묻지 않고 바로 튼다.
     * 운전자가 먼저 청했으므로 안내 간격(5분)은 따지지 않는다. 틀기 시작했으면 true.
     */
    fun listenNow(): Boolean {
        if (phase == Phase.PROMPTING) {
            accept()
            return true
        }
        if (phase != Phase.IDLE || !canPrompt()) return false
        val group = policy.groupFrom(approaches)
        if (group.isEmpty() || !audio.begin()) return false
        asked += group.map { it.message.id }
        refreshApproaches()
        queue = group.map { it.message }
        index = 0
        lastHeard = null
        lastThanks = null
        lastPromptAtMs = clock()
        keys.activate()
        EventLog.add("안내", "Gleo 요청으로 바로 들려 드려요 · ${queue.size}건")
        playAt(0)
        return true
    }

    fun accept() {
        if (phase != Phase.PROMPTING) return
        handler.removeCallbacks(promptTimeout)
        voice.stop()
        EventLog.add("안내", "듣기")
        playAt(0)
    }

    /** 묻는 중이면 '안 들을게요'(listen은 보내지 않음), 듣는 중이면 이 한마디를 넘기고 다음 한마디로. */
    fun skip() {
        when (phase) {
            Phase.PROMPTING -> {
                EventLog.add("안내", "넘기기")
                endSession()
            }
            Phase.LOADING -> next()
            Phase.PLAYING -> {
                val message = current ?: return
                report(message, ListenResult.SKIPPED, audio.stop())
                lastHeard = message
                lastThanks = null
                next()
            }
            Phase.AFTER -> next()
            Phase.IDLE -> Unit
        }
    }

    /** 묶음의 남은 한마디까지 모두 그만 듣는다. 틀고 있던 한마디는 넘긴 것으로 알린다. */
    fun stop() {
        if (phase == Phase.PLAYING) {
            current?.let { message ->
                report(message, ListenResult.SKIPPED, audio.stop())
                lastHeard = message
                lastThanks = null
            }
        }
        EventLog.add("안내", "그만 듣기")
        endSession()
    }

    fun thanks() {
        val message = current?.takeIf { phase == Phase.PLAYING || phase == Phase.AFTER } ?: lastHeard ?: return
        api.thanks(message.id) { result ->
            result.onSuccess {
                if (!it.alreadyThanked) thankedCount++
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

    /**
     * 안내 카드에 세울 길동무: 묻는 중이면 묶음 전체, 듣는 중이면 지금 한마디, 그 뒤에는 방금 들은 한마디.
     * 고마워요 결과가 있으면 그 단계로 보여준다(꽃을 드는 순간).
     */
    fun shownMates(): List<Shown> = when (phase) {
        Phase.PROMPTING -> queue.map { Shown(it.id, it.stage) }
        // 조용할 때: 방금 들은 길동무, 없으면 그다음에 만날 길동무를 미리 보여준다.
        Phase.IDLE -> lastHeard?.let { listOf(Shown(it.id, lastThanks?.stage ?: it.stage)) }
            ?: nextAhead().map { Shown(it.message.id, it.message.stage) }
        else -> listOfNotNull(current?.let { Shown(it.id, lastThanks?.stage ?: it.stage) })
    }

    /** 카드의 작은 표시(언제·비·고마워요·들은 수)에 쓸 한마디. */
    fun contextMessage(): NearbyMessage? = when (phase) {
        Phase.PROMPTING -> queue.firstOrNull()
        Phase.IDLE -> lastHeard ?: nextAhead().firstOrNull()?.message
        else -> current
    }

    /** 지금 묻거나 듣는 묶음 다음에 만날 묶음(가장 가까운 한마디와 거기서 50m 안). */
    fun nextAhead(): List<Approach> {
        val now = queue.map { it.id }.toSet()
        return policy.groupFrom(approaches.filter { it.message.id !in now })
    }

    fun trip() = Trip(met = met.size, heard = heardCount, thanked = thankedCount)

    /** 듣는 중이면 0~1 진행 정도. 진행 막대에 쓴다(1초마다 칸만 바뀌는 막대라 주행 중에도 쓸 수 있다). */
    fun progress(): Float? {
        if (phase != Phase.PLAYING) return null
        val total = current?.durationMs?.takeIf { it > 0 } ?: return null
        return (audio.positionMs.toFloat() / total).coerceIn(0f, 1f)
    }

    /** 묶음을 듣는 중이면 (지금 차례, 모두). 한 개면 null. */
    fun order(): Pair<Int, Int>? = if (queue.size > 1 && phase != Phase.IDLE && phase != Phase.PROMPTING) (index + 1) to queue.size else null

    fun release() {
        handler.removeCallbacks(promptTimeout)
        handler.removeCallbacks(afterTimeout)
        voice.release()
        audio.release()
        keys.release()
        api.shutdown()
    }

    /** 앞쪽 길 한 줄. 자세한 위치는 길 띠가 보여준다. */
    private fun aheadLine(): String {
        if (!api.configured) return serverStatus
        if (drive?.heading == null) return "움직이기 시작하면 앞쪽 길을 살펴요"
        if (approaches.isEmpty()) return "앞쪽 길은 아직 조용해요"
        // 서버가 센 수에서, 조회 뒤에 지나쳤거나 이미 물어본 것만큼 뺀다.
        val count = (totalAhead - (messages.size - approaches.size)).coerceAtLeast(approaches.size)
        return "앞쪽 길에 길동무 ${count}명이 한마디를 남겼어요"
    }

    fun status(): Status {
        val message = current
        val order = if (queue.size > 1) "${index + 1}/${queue.size}" else null
        return when (phase) {
            Phase.IDLE -> idleStatus()
            Phase.PROMPTING -> {
                val left = if (deadlineMs == 0L) ANSWER_TIMEOUT_MS / 1_000 else ((deadlineMs - clock()) / 1_000).coerceAtLeast(0)
                val distance = drive?.let { d -> message?.let { Geo.distanceMeters(d.lat, d.lon, it.lat, it.lon).roundToInt() } }
                val how = if (voiceReady) "\"응\"이나 \"지나가\"라고 말해도 돼요" else "핸들 재생 버튼으로도 들어요"
                Status(
                    promptSentence(),
                    listOfNotNull(distance?.let { "앞쪽 ${it}m" }, message?.stage?.label?.takeIf { queue.size == 1 }, "${left}초", how)
                        .joinToString(" · "),
                )
            }
            Phase.LOADING -> Status("한마디를 불러오는 중이에요", order.orEmpty())
            Phase.PLAYING -> Status(
                "길동무의 ${kindWord(message)}를 듣고 있어요",
                // 묶음 순서는 카드의 점(● ○ ○ 1/3)이 보여주므로 여기엔 넣지 않는다.
                listOfNotNull(
                    "%.0f / %.0f초".format(Locale.KOREA, audio.positionMs / 1_000.0, (message?.durationMs ?: 0) / 1_000.0),
                    audio.state,
                ).joinToString(" · "),
            )
            Phase.AFTER -> {
                val thanked = lastThanks?.let { "고마워요 ${it.thanksCount}개 · " }.orEmpty()
                Status(
                    if (voiceReady) "좋았다면 \"고마워\"라고 해 주세요" else "좋았다면 고마워요를 눌러 주세요",
                    thanked + "남긴 길동무에게 전해 드릴게요" + if (index + 1 < queue.size) " · 곧 다음 길동무 ($order)" else "",
                )
            }
        }
    }

    /** "길동무가 인사 한마디를 남겼어요. 들어 볼까요?" 또는 "이 자리에 길동무 6명이 한마디씩 남겼어요. 들어 볼까요?" */
    private fun promptSentence(): String {
        val message = current
        return when {
            queue.size > 1 -> "이 자리에 길동무 ${queue.size}명이 한마디씩 남겼어요. 들어 볼까요?"
            message?.stage == Stage.FLOWER -> "꽃을 든 길동무가 ${kindWord(message)}를 남겼어요. 들어 볼까요?"
            else -> "길동무가 ${kindWord(message)}를 남겼어요. 들어 볼까요?"
        }
    }

    /** "인사 한마디", 종류를 모르면 그냥 "한마디". */
    private fun kindWord(message: NearbyMessage?): String =
        message?.kind?.takeIf { it != Kind.UNKNOWN }?.let { "${it.label} 한마디" } ?: "한마디"

    /**
     * 길 띠에 그릴 한마디: 지금 묻거나 틀고 있는 묶음과 앞쪽 후보를 가까운 순으로.
     * 묶음 안의 한마디를 이미 지나쳤으면 차 바로 앞(0m)에 둔다.
     */
    fun shownAhead(): List<Approach> {
        val d = drive ?: return emptyList()
        val now = queue.drop(index).map { m ->
            val heading = d.heading
            val passed = heading != null && Geo.angleDiff(Geo.bearing(d.lat, d.lon, m.lat, m.lon), heading) > 90
            Approach(m, if (passed) 0.0 else Geo.distanceMeters(d.lat, d.lon, m.lat, m.lon), etaSec = null)
        }
        val ids = now.map { it.message.id }.toSet()
        return (now + approaches.filter { it.message.id !in ids }).sortedBy { it.distanceM }
    }

    /** 지금 묻거나 틀고 있는 한마디의 id. 길 띠에서 강조한다. 묻는 중에는 묶음 전체를 강조한다. */
    fun isHighlighted(id: String): Boolean = when (phase) {
        Phase.IDLE -> false
        Phase.PROMPTING -> queue.any { it.id == id }
        else -> current?.id == id
    }

    private fun idleStatus(): Status {
        val wait = lastPromptAtMs?.let { policy.promptIntervalMs - (clock() - it) }?.takeIf { it > 0 }
        val next = if (wait != null) {
            "다음 안내는 %d분 %02d초 뒤부터예요".format(Locale.KOREA, wait / 60_000, wait / 1_000 % 60)
        } else {
            "길동무에게 10초 안에 닿으면 알려 드려요"
        }
        val thanks = lastThanks
        val ahead = nextAhead()
        val name = nickname()
        return when {
            thanks?.stage == Stage.FLOWER -> Status("길동무가 꽃을 들었어요!", "이 한마디에 고마워요가 ${thanks.thanksCount}개 모였어요")
            thanks != null -> Status("길동무에게 고마워요를 전했어요", "지금 ${thanks.thanksCount}개예요 · $next")
            lastHeard != null -> Status("방금 들은 한마디, 좋았나요?", "고마워요를 눌러 주세요 · $next")
            // 앞쪽에 길동무가 있으면 미리 알려 준다.
            ahead.isNotEmpty() -> Status("앞쪽 ${ahead.first().distanceM.roundToInt()}m에 ${whoAhead(ahead)} 있어요", next)
            name != null && api.configured && drive?.heading != null -> Status("오늘도 안전 운전해요, ${name}님", "앞쪽 길은 아직 조용해요 · $next")
            else -> Status(aheadLine(), next)
        }
    }

    /** "길동무 6명이", "꽃을 든 길동무가", "길동무가" */
    private fun whoAhead(group: List<Approach>): String = when {
        group.size > 1 -> "길동무 ${group.size}명이"
        group.first().message.stage == Stage.FLOWER -> "꽃을 든 길동무가"
        else -> "길동무가"
    }

    private fun poll(state: DriveState) {
        val heading = state.heading ?: return
        polling = true
        lastPoll = GuidePolicy.PollMark(state.lat, state.lon, clock())
        // 모여 있는 한마디를 한 번에 묻기 위해 서버가 주는 만큼(최대 10개) 받는다.
        api.nearby(NearbyQuery(state.lat, state.lon, heading, state.speedKmh, filterOn(), limit = NEARBY_LIMIT)) { result ->
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
        met += approaches.map { it.message.id }
    }

    private fun prompt(group: List<Approach>) {
        asked += group.map { it.message.id }
        refreshApproaches()
        if (!audio.begin()) {
            EventLog.add("안내", "오디오 포커스를 못 받아 이번 한마디는 건너뛰어요")
            return
        }
        phase = Phase.PROMPTING
        queue = group.map { it.message }
        index = 0
        lastHeard = null
        lastThanks = null
        lastPromptAtMs = clock()
        deadlineMs = 0L
        keys.activate()
        audio.chime()
        val sentence = promptSentence()
        EventLog.add("안내", "앞쪽 ${group.first().distanceM.roundToInt()}m · $sentence")
        // 알림음이 끝난 뒤 문장을 읽고, 다 읽은 뒤부터 대답을 기다린다(내 TTS를 대답으로 듣지 않도록).
        handler.postDelayed({
            if (phase == Phase.PROMPTING) audio.speak(sentence) { waitForAnswer() }
        }, CHIME_MS)
    }

    private fun waitForAnswer() {
        if (phase != Phase.PROMPTING) return
        deadlineMs = clock() + ANSWER_TIMEOUT_MS
        handler.postDelayed(promptTimeout, ANSWER_TIMEOUT_MS)
        if (!voiceReady) return
        voice.listen(ANSWER_TIMEOUT_MS) { answer, _ ->
            when (answer) {
                Answer.LISTEN -> accept()
                Answer.SKIP, Answer.STOP -> skip()
                // 묻는 중의 "고마워"는 뜻이 모호하니 듣지 않은 것으로 보고 계속 기다린다.
                Answer.THANKS -> return@listen false
            }
            true
        }
    }

    private fun playAt(i: Int) {
        index = i
        val message = current ?: return endSession()
        phase = Phase.LOADING
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
                next()
            }
        }
    }

    private fun play(message: NearbyMessage, file: File) {
        phase = Phase.PLAYING
        // 올린 길이(최대 15초)보다 1초 넘게 틀지 않는다.
        val maxMs = message.durationMs.toLong().coerceIn(1_000L, MAX_MESSAGE_MS) + 1_000L
        audio.play(file, maxMs) { completed, playedMs ->
            report(message, if (completed) ListenResult.COMPLETED else ListenResult.SKIPPED, playedMs)
            lastHeard = message
            lastThanks = null
            afterMessage()
        }
    }

    /** 한마디 하나를 다 들은 뒤 잠깐 "고마워"를 기다린다. 재생 중에는 한마디 소리를 대답으로 잘못 들을 수 있어 듣지 않는다. */
    private fun afterMessage() {
        phase = Phase.AFTER
        handler.postDelayed(afterTimeout, AFTER_MS)
        if (!voiceReady) return
        voice.listen(AFTER_MS) { answer, _ ->
            when (answer) {
                Answer.THANKS -> {
                    thanks()
                    next()
                }
                Answer.SKIP, Answer.LISTEN -> next()
                Answer.STOP -> stop()
            }
            true
        }
    }

    /** 묶음의 다음 한마디로. 남은 게 없으면 안내를 마친다. */
    private fun next() {
        handler.removeCallbacks(afterTimeout)
        voice.stop()
        if (index + 1 < queue.size) playAt(index + 1) else endSession()
    }

    private fun report(message: NearbyMessage, result: ListenResult, playedMs: Long) {
        if (result == ListenResult.COMPLETED) heardCount++
        EventLog.add("재생", "${if (result == ListenResult.COMPLETED) "끝까지 들음" else "중간에 넘김"} (${playedMs}ms)")
        api.listen(message.id, result, playedMs) { sent ->
            sent.onFailure { EventLog.add("서버", "들은 결과를 보내지 못했어요: ${it.message}") }
        }
    }

    private fun onFocusLost() {
        stop()
    }

    private fun endSession() {
        handler.removeCallbacks(promptTimeout)
        handler.removeCallbacks(afterTimeout)
        voice.stop()
        audio.end()
        keys.deactivate()
        phase = Phase.IDLE
        // 마지막에 들은 한마디는 lastHeard로 남기고 묶음은 비운다.
        queue = emptyList()
        index = 0
    }

    companion object {
        private const val ANSWER_TIMEOUT_MS = 8_000L
        private const val AFTER_MS = 4_000L
        private const val CHIME_MS = 500L
        private const val MAX_MESSAGE_MS = 15_000L
        private const val NEARBY_LIMIT = 10
    }
}
