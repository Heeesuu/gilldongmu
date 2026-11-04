package com.gildongmu.app.guide

/**
 * 말로 한 대답을 알아듣는다. 음성 인식이 돌려준 글자에서 낱말을 찾는 단순한 방식이다 (docs/api.md 4장).
 * - "안 들을래", "다음에 들을게"처럼 거절 낱말이 섞이면 듣기보다 넘기기로 본다.
 * - "고마워"는 다른 낱말보다 먼저 본다. 듣고 난 뒤에 가장 많이 할 말이기 때문이다.
 */
object AnswerWords {

    enum class Answer { LISTEN, SKIP, STOP, THANKS }

    private val thanks = listOf("고마워", "고맙", "감사", "땡큐", "thank")
    private val stop = listOf("그만", "멈춰", "꺼 줘", "꺼줘", "조용")
    private val skip = listOf("아니", "아뇨", "안 들", "안들", "됐어", "괜찮아", "넘겨", "넘어가", "다음", "패스", "싫어", "나중", "skip")
    private val listen = listOf(" 응 ", " 어 ", " 네 ", " 예 ", "그래", "좋아", "들어", "들을", "들려", "듣기", "틀어", "재생", "오케이", " ok ", "yes")

    fun match(text: String): Answer? {
        // 앞뒤에 빈칸을 붙여 "응", "네"처럼 짧은 낱말은 낱말 하나일 때만 맞춘다. 문장 부호는 빈칸으로 바꾼다.
        val t = " " + text.lowercase().replace(Regex("[.,!?~]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return when {
            thanks.any { it in t } -> Answer.THANKS
            stop.any { it in t } -> Answer.STOP
            skip.any { it in t } -> Answer.SKIP
            listen.any { it in t } -> Answer.LISTEN
            else -> null
        }
    }
}
