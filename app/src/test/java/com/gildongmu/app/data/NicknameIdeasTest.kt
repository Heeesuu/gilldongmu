package com.gildongmu.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NicknameIdeasTest {

    // 서버 규칙(docs/api.md 3.9)과 같은 형식: 2~10자, 한글·영문·숫자와 낱말 사이 빈칸 하나
    private val pattern = Regex("^[가-힣a-zA-Z0-9]+( [가-힣a-zA-Z0-9]+)*$")

    @Test
    fun `추천 닉네임은 모두 서버 규칙에 맞는다`() {
        NicknameIdeas.all().forEach {
            assertTrue("$it: 길이", it.codePointCount(0, it.length) in 2..10)
            assertTrue("$it: 글자", pattern.matches(it))
        }
        assertTrue(NicknameIdeas.pick(Random(1)) in NicknameIdeas.all())
    }
}
