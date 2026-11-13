package com.gildongmu.app.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OpusPcmTest {

    @Test
    fun `16kHz 모노는 그대로 둔다`() {
        val samples = shortArrayOf(1, -2, 300, -400)
        assertArrayEquals(samples, OpusPcm.toMono16k(samples, 16_000, 1))
    }

    @Test
    fun `48kHz 스테레오는 두 채널을 합치고 세 개씩 평균 내어 16kHz로 줄인다`() {
        // 프레임마다 (왼쪽, 오른쪽). 모노로 합치면 [10, 20, 30, 40, 50, 60]
        val stereo = shortArrayOf(0, 20, 10, 30, 20, 40, 30, 50, 40, 60, 50, 70)
        val out = OpusPcm.toMono16k(stereo, 48_000, 2)
        assertArrayEquals(shortArrayOf(20, 50), out)
    }

    @Test
    fun `정수배가 아닌 표본율은 선형 보간으로 길이를 맞춘다`() {
        val samples = ShortArray(44_100) { 100 }
        val out = OpusPcm.toMono16k(samples, 44_100, 1)
        assertEquals(16_000, out.size)
        assertEquals(100.toShort(), out[8_000])
    }

    @Test
    fun `PCM 바이트는 16비트 리틀 엔디언이다`() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()), OpusPcm.toBytes(shortArrayOf(0x1234, -1)))
    }
}
