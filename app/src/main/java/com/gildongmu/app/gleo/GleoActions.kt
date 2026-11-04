package com.gildongmu.app.gleo

/**
 * Gleo 명령을 실제로 처리할 곳(본 화면)을 잇는다. 화면이 떠 있는 동안만 [target]이 있다.
 * 화면이 없으면 듣는 흐름·녹음도 없으므로 처리하지 않고 실패로 돌려준다.
 */
object GleoActions {

    fun interface Target {
        fun onGleo(command: GleoCommand): GleoReply
    }

    @Volatile
    var target: Target? = null

    fun handle(command: GleoCommand): GleoReply =
        target?.onGleo(command) ?: GleoReply(GleoReply.Status.FAIL, "Gildongmu is not open right now.", GleoReply.Mode.VOICE)
}
