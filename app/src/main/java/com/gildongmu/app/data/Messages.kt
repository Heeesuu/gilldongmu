package com.gildongmu.app.data

/** 서버 응답을 옮겨 담는 값들. 필드 이름과 뜻은 docs/api.md 3장을 따른다. */

/** `stage`: 씨앗(검수 중) → 새싹(공개) → 꽃(고마워요 5개 이상) → 추억(유효기간 끝). */
enum class Stage(val wire: String, val label: String) {
    SEED("seed", "씨앗"),
    SPROUT("sprout", "새싹"),
    FLOWER("flower", "꽃"),
    MEMORY("memory", "추억");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: SPROUT
    }
}

/** `kind`: 서버가 검수하면서 나누는 종류. */
enum class Kind(val wire: String, val label: String) {
    ROAD("road", "길 상황"),
    PLACE("place", "장소 이야기"),
    GREETING("greeting", "인사"),
    UNKNOWN("", "한마디");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** `review`: 남긴 사람에게만 보이는 검수 결과. */
enum class Review(val wire: String, val label: String) {
    PENDING("pending", "검수 중"),
    CLEAN("clean", "공개"),
    PROFANITY("profanity", "필터 끈 사람에게만 들림"),
    HOLD("hold", "확인할 때까지 비공개"),
    BLOCKED("blocked", "공개 안 함");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: PENDING
    }
}

/** `POST /v1/messages/{id}/listen`의 `result`. */
enum class ListenResult(val wire: String) {
    COMPLETED("completed"),
    SKIPPED("skipped"),
}

data class NearbyQuery(
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val speedKmh: Double,
    val filterOn: Boolean = true,
    val limit: Int = 3,
)

data class NearbyMessage(
    val id: String,
    val stage: Stage,
    val kind: Kind,
    val lat: Double,
    val lon: Double,
    /** null이면 방향을 모른 채 남겨진 한마디라 양쪽 방향 모두에 들린다. */
    val heading: Double?,
    val distanceM: Int,
    val etaSec: Int?,
    val durationMs: Int,
    val thanksCount: Int,
    val audioUrl: String,
    /** 카드의 작은 표시("어제 저녁 · 비 오는 날 · 37명이 들음")에 쓴다. 남긴 사람은 싣지 않는다. */
    val listenCount: Int = 0,
    val recordedAt: String = "",
    val wiperOn: Boolean? = null,
    val headlightOn: Boolean? = null,
)

data class NearbyResult(
    val totalAhead: Int,
    val messages: List<NearbyMessage>,
    val nextPollSec: Int,
)

/** `POST /v1/messages`의 `meta`. 위치·속도는 녹음을 시작한 순간의 값이고, 방향은 모르면 null이다. */
data class UploadMeta(
    val recordedAt: String,
    val lat: Double,
    val lon: Double,
    val heading: Double?,
    val speedKmh: Double,
    val durationMs: Long,
    val wiperOn: Boolean?,
    val headlightOn: Boolean?,
)

data class UploadResult(val id: String, val stage: Stage)

/** `GET /v1/me/messages`의 한 항목. */
data class MyMessage(
    val id: String,
    val stage: Stage,
    val kind: Kind,
    val review: Review,
    val durationMs: Int,
    val listenCount: Int,
    val thanksCount: Int,
    val createdAt: String,
)

data class ThanksResult(
    val thanksCount: Int,
    val stage: Stage,
    val alreadyThanked: Boolean,
)

/** 서버가 돌려준 오류. `code`는 docs/api.md 1장의 오류 코드, `message`는 서버가 쓴 한국어 문장이다. */
class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/** 내 길동무가 걸친 소품. `wire`는 서버 값이다 (docs/api.md 3.9). */
enum class LookItem(val wire: String, val label: String) {
    NONE("none", "없음"),
    HAT("hat", "모자"),
    SCARF("scarf", "목도리"),
    BACKPACK("backpack", "배낭");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/** 내 길동무 모습: 몸 색(0~5)과 소품. */
data class Look(val color: Int, val item: LookItem)

/** 내 길동무. 닉네임은 내 기기에서만 보인다. `nextChangeAt`은 닉네임을 다시 바꿀 수 있는 시각(ISO-8601). */
data class Profile(val nickname: String, val look: Look, val nextChangeAt: String?)
