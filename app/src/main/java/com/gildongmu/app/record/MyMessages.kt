package com.gildongmu.app.record

import android.os.Handler
import android.os.Looper
import com.gildongmu.app.EventLog
import com.gildongmu.app.data.MyMessage
import com.gildongmu.app.data.Review
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.Stage
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 내가 남긴 한마디의 단계와 반응 (`GET /v1/me/messages`).
 * 검수 중인 한마디가 있으면 5초마다, 없으면 1분마다 다시 읽고, 씨앗이 새싹이 되거나 꽃이 피면 알려 준다.
 */
class MyMessages(private val api: ServerApi) {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh() }
    private val timeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

    /** 최근 순 (서버가 최대 50개). 정차 중 '내 꽃밭'에 그린다. */
    var items: List<MyMessage> = emptyList()
        private set
    /** 검수 결과를 기다리는 한마디. 방금 올린 것은 서버 목록에 나오기 전에 여기 넣어 둔다. */
    private val waiting = mutableSetOf<String>()
    private var stages: Map<String, Stage> = emptyMap()
    private var running = false

    /** 가장 최근 소식 (예: "새싹이 돋았어요"). 화면에 한 줄로 보여준다. */
    var news: String? = null
        private set

    fun start() {
        running = true
        refresh()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** 방금 올린 한마디. 서버 검수가 바로 끝나도 소식을 놓치지 않도록 기다리는 목록에 먼저 넣는다. */
    fun track(id: String) {
        waiting += id
        refresh()
    }

    /** 한마디 한 줄 (아이콘 옆에 붙는 글자). */
    fun lineOf(m: MyMessage): String {
        val review = if (m.review == Review.CLEAN) "" else " (${m.review.label})"
        return "${timeOf(m.createdAt)} · ${m.kind.label} · ${m.stage.label}$review\n들은 ${m.listenCount}명 · 고마워요 ${m.thanksCount}"
    }

    private fun refresh() {
        handler.removeCallbacks(tick)
        api.myMessages { result ->
            result.onSuccess { list ->
                announce(list)
                items = list
                stages = list.associate { it.id to it.stage }
                waiting += list.filter { it.review == Review.PENDING }.map { it.id }
            }
            if (running) {
                val delay = if (waiting.isNotEmpty()) PENDING_POLL_MS else IDLE_POLL_MS
                handler.postDelayed(tick, delay)
            }
        }
    }

    private fun announce(list: List<MyMessage>) {
        list.forEach { m ->
            if (m.id in waiting && m.review != Review.PENDING) {
                waiting -= m.id
                tell(
                    when (m.review) {
                        Review.CLEAN, Review.PROFANITY -> "새싹이 돋았어요 · ${m.kind.label} 한마디가 이제 같은 길의 차에 들려요"
                        else -> "검수 결과 · ${m.review.label}"
                    },
                )
            }
            val before = stages[m.id]
            if (before != null && before != Stage.FLOWER && m.stage == Stage.FLOWER) {
                tell("꽃이 피었어요 · 고마워요 ${m.thanksCount}개")
            }
        }
    }

    private fun tell(text: String) {
        news = text
        EventLog.add("내 한마디", text)
    }

    private fun timeOf(createdAt: String) = runCatching {
        OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).format(timeFormat)
    }.getOrDefault(createdAt)

    companion object {
        private const val PENDING_POLL_MS = 5_000L
        private const val IDLE_POLL_MS = 60_000L

        /** 꽃 소식 (기획서 4번 시나리오): "내 한마디를 N명이 들었고 M명이 고마워했어요". 남긴 게 없으면 null. */
        fun bloomSummary(items: List<MyMessage>): String? {
            if (items.isEmpty()) return null
            val listened = items.sumOf { it.listenCount }
            val thanked = items.sumOf { it.thanksCount }
            val flowers = items.count { it.stage == Stage.FLOWER }
            val bloom = if (flowers > 0) " 꽃이 ${flowers}송이 피었어요." else ""
            return "한마디 ${items.size}개를 남겼어요. ${listened}명이 들었고 ${thanked}명이 고마워했어요.$bloom"
        }
    }
}
