package com.gildongmu.app.data

import java.io.ByteArrayOutputStream

/**
 * `multipart/form-data` 본문을 만든다. 업로드는 문자열 필드 몇 개와 파일 하나뿐이라
 * 라이브러리 없이 직접 만든다. 안드로이드 API를 쓰지 않아 JVM 단위 테스트로 확인할 수 있다.
 */
object Multipart {

    class FilePart(val name: String, val filename: String, val contentType: String, val bytes: ByteArray)

    fun contentType(boundary: String) = "multipart/form-data; boundary=$boundary"

    fun body(boundary: String, fields: List<Pair<String, String>>, file: FilePart): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(text: String = "") = out.write("$text\r\n".toByteArray(Charsets.UTF_8))

        fields.forEach { (name, value) ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("Content-Type: text/plain; charset=utf-8")
            line()
            line(value)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"${file.name}\"; filename=\"${file.filename}\"")
        line("Content-Type: ${file.contentType}")
        line()
        out.write(file.bytes)
        line()
        line("--$boundary--")
        return out.toByteArray()
    }
}
