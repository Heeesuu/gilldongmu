package com.gildongmu.app.data

import android.content.Context

/**
 * 기기에만 저장하는 앱 설정.
 * 비속어 필터는 기본으로 켜져 있다. 켜기는 언제든, 끄기는 정차 중에 한 번 더 확인받고 한다 (기획서 3번).
 * 말로 대답하기도 기본으로 켜져 있다. 마이크는 대답을 기다리는 몇 초 동안만 연다 (docs/api.md 4장).
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var filterOn: Boolean
        get() = prefs.getBoolean(KEY_FILTER_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_ON, value).apply()

    /** 서버에 저장한 내 길동무를 기억해 둔다. 서버에 닿지 않을 때도 화면에 보여주기 위함이다. */
    var profile: Profile?
        get() {
            val nickname = prefs.getString(KEY_NICKNAME, null) ?: return null
            val look = Look(prefs.getInt(KEY_LOOK_COLOR, 0), LookItem.of(prefs.getString(KEY_LOOK_ITEM, null).orEmpty()))
            return Profile(nickname, look, prefs.getString(KEY_NEXT_CHANGE, null))
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_NICKNAME)
                } else {
                    putString(KEY_NICKNAME, value.nickname)
                    putInt(KEY_LOOK_COLOR, value.look.color)
                    putString(KEY_LOOK_ITEM, value.look.item.wire)
                    putString(KEY_NEXT_CHANGE, value.nextChangeAt)
                }
            }.apply()
        }

    var voiceAnswer: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ANSWER, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ANSWER, value).apply()

    companion object {
        // DeviceId와 같은 파일을 쓴다.
        private const val PREFS = "gildongmu"
        private const val KEY_FILTER_ON = "filter_on"
        private const val KEY_VOICE_ANSWER = "voice_answer"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LOOK_COLOR = "look_color"
        private const val KEY_LOOK_ITEM = "look_item"
        private const val KEY_NEXT_CHANGE = "next_nickname_change_at"
    }
}
