package com.gildongmu.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 확인 결과를 화면과 Logcat(태그: Gildongmu)에 함께 남기는 기록장.
 * 에뮬레이터 밖에서도 `adb logcat -s Gildongmu`로 결과를 볼 수 있게 하기 위함.
 */
object EventLog {
    private const val TAG = "Gildongmu"
    private const val MAX_LINES = 30

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    @Synchronized
    fun add(area: String, message: String) {
        Log.i(TAG, "[$area] $message")
        lines.addFirst("${timeFormat.format(Date())} [$area] $message")
        while (lines.size > MAX_LINES) lines.removeLast()
    }

    @Synchronized
    fun recent(): String = lines.joinToString("\n")
}
