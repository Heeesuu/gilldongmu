package com.gildongmu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartTest {

    @Test
    fun `서버가 읽는 meta 필드와 audio 파일 파트를 만든다`() {
        val audio = byteArrayOf(0x4F, 0x67, 0x67, 0x53) // "OggS"
        val body = Multipart.body(
            boundary = "b",
            fields = listOf("meta" to """{"lat":37.5}"""),
            file = Multipart.FilePart("audio", "1.ogg", "audio/ogg", audio),
        ).decodeToString()

        val expected = "--b\r\n" +
            "Content-Disposition: form-data; name=\"meta\"\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "{\"lat\":37.5}\r\n" +
            "--b\r\n" +
            "Content-Disposition: form-data; name=\"audio\"; filename=\"1.ogg\"\r\n" +
            "Content-Type: audio/ogg\r\n" +
            "\r\n" +
            "OggS\r\n" +
            "--b--\r\n"
        assertEquals(expected, body)
    }

    @Test
    fun `Content-Type 헤더에 boundary를 담는다`() {
        assertTrue(Multipart.contentType("abc").endsWith("boundary=abc"))
    }
}
