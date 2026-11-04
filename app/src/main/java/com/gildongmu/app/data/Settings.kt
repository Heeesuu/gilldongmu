package com.gildongmu.app.data

import android.content.Context

/**
 * 기기에만 저장하는 앱 설정.
 * 비속어 필터는 기본으로 켜져 있다. 켜기는 언제든, 끄기는 정차 중에 한 번 더 확인받고 한다 (기획서 3번).
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var filterOn: Boolean
        get() = prefs.getBoolean(KEY_FILTER_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_ON, value).apply()

    companion object {
        // DeviceId와 같은 파일을 쓴다.
        private const val PREFS = "gildongmu"
        private const val KEY_FILTER_ON = "filter_on"
    }
}
