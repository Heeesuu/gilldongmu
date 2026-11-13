package com.gildongmu.app.data

import kotlin.random.Random

/**
 * 처음 실행할 때 추천하는 닉네임("느긋한 고라니"). 그대로 써도 되고 "다시 뽑기"로 바꿀 수 있다.
 * 서버 규칙(2~10자, 한글과 빈칸)에 맞는 낱말만 넣었다.
 */
object NicknameIdeas {

    private val adjectives = listOf(
        "느긋한", "반짝이는", "씩씩한", "다정한", "졸린", "용감한", "상냥한", "부지런한", "명랑한", "포근한", "조용한", "신나는",
    )
    private val nouns = listOf(
        "고라니", "은행나무", "부엉이", "다람쥐", "해바라기", "돌고래", "수달", "고양이", "민들레", "거북이", "참새", "소나무",
    )

    fun pick(random: Random = Random.Default): String = "${adjectives.random(random)} ${nouns.random(random)}"

    /** 테스트용: 만들 수 있는 모든 조합. */
    fun all(): List<String> = adjectives.flatMap { a -> nouns.map { n -> "$a $n" } }
}
