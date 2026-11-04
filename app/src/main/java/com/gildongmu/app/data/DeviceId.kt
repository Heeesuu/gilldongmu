package com.gildongmu.app.data

import android.content.Context
import java.util.UUID

/**
 * 로그인 대신 기기를 구분하는 익명 ID (docs/api.md 1장의 `X-Device-Id`).
 * 처음 실행할 때 UUID v4를 만들어 두고, 앱 데이터를 지우면 새로 만든다.
 */
object DeviceId {
    private const val PREFS = "gildongmu"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
