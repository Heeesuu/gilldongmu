package com.gildongmu.app.gleo

/**
 * Gleo AI가 보내는 명령 (Gleo Interface, docs/api.md 4장). `assets/schema.json`의 함수 이름과 짝을 이룬다.
 * 스키마에는 함수를 셋까지만 둘 수 있어서, 듣기·넘기기·그만은 한 함수의 `command` 값으로 받는다.
 */
enum class GleoCommand { LISTEN, SKIP, STOP, THANK, RECORD }

/** 앱이 Gleo에 돌려줄 결과. `message`는 Gleo가 대답을 만들 때 쓰므로 영어 문장으로 쓴다 (Pleos 콜백 규칙). */
data class GleoReply(val status: Status, val message: String, val mode: Mode) {
    enum class Status(val wire: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

    /** VOICE: Gleo가 말로 대답. BEEP: 효과음만. SILENT: 소리 없음(앱이 바로 소리를 낼 때). */
    enum class Mode(val wire: String) { VOICE("voice"), BEEP("beep"), SILENT("silent") }
}

object GleoCommands {
    const val CONTROL = "CONTROL_ROAD_MESSAGES"
    const val THANK = "THANK_ROAD_MESSAGE"
    const val RECORD = "RECORD_ROAD_MESSAGE"

    /** 인텐트 액션과 `command` 값(CONTROL일 때만)을 명령으로 바꾼다. 모르는 값이면 null. */
    fun of(action: String?, command: String?): GleoCommand? = when (action) {
        CONTROL -> when (command?.lowercase()) {
            "listen" -> GleoCommand.LISTEN
            "skip" -> GleoCommand.SKIP
            "stop" -> GleoCommand.STOP
            else -> null
        }
        THANK -> GleoCommand.THANK
        RECORD -> GleoCommand.RECORD
        else -> null
    }

    /** 명령이 어느 함수에서 왔는지. 콜백의 `functionName`에 쓴다. */
    fun functionOf(command: GleoCommand) = when (command) {
        GleoCommand.LISTEN, GleoCommand.SKIP, GleoCommand.STOP -> CONTROL
        GleoCommand.THANK -> THANK
        GleoCommand.RECORD -> RECORD
    }
}
