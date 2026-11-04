package com.gildongmu.app.guide

import com.gildongmu.app.guide.AnswerWords.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerWordsTest {

    @Test
    fun `들을까요에 대한 대답`() {
        listOf("응", "응.", "어", "네", "예", "그래", "좋아", "들어볼게", "들려줘", "틀어 줘", "오케이").forEach {
            assertEquals(it, Answer.LISTEN, AnswerWords.match(it))
        }
        listOf("아니", "아니요", "됐어", "괜찮아요", "넘겨", "지나갈게요", "다음", "패스", "안 들을래", "다음에 들을게").forEach {
            assertEquals(it, Answer.SKIP, AnswerWords.match(it))
        }
    }

    @Test
    fun `듣고 난 뒤의 대답`() {
        listOf("고마워", "고마워요", "고맙습니다", "감사합니다", "좋았어 고마워").forEach {
            assertEquals(it, Answer.THANKS, AnswerWords.match(it))
        }
        listOf("그만", "그만 들을래", "멈춰").forEach { assertEquals(it, Answer.STOP, AnswerWords.match(it)) }
    }

    @Test
    fun `짧은 낱말은 다른 낱말 속에 있으면 맞추지 않는다`() {
        assertNull(AnswerWords.match("응원해"))
        assertNull(AnswerWords.match("내비 소리 줄여"))
        assertNull(AnswerWords.match(""))
    }
}
