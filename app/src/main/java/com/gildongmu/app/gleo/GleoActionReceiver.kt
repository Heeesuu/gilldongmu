package com.gildongmu.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gildongmu.app.EventLog
import org.json.JSONObject

/**
 * Gleo AI가 보내는 액션을 받는다. 스키마의 `component`가 "service"라서 Gleo는 sendBroadcast로 보낸다.
 * 처리한 결과는 7초 안에 `APP_ACTION_RESPONSE` 브로드캐스트로 Gleo에 돌려준다 (Pleos Callback 규칙).
 * onReceive는 메인 스레드에서 불린다.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val param = intent.getStringExtra(EXTRA_PARAM)
        val commandValue = param?.let { runCatching { JSONObject(it).optString("command") }.getOrNull() }
        val command = GleoCommands.of(intent.action, commandValue)
        EventLog.add("Gleo", "받은 명령: ${intent.action} ${param.orEmpty()} → ${command?.name ?: "모름"}")
        val reply = if (command == null) {
            GleoReply(GleoReply.Status.FAIL, "Gildongmu does not know this request.", GleoReply.Mode.VOICE)
        } else {
            GleoActions.handle(command)
        }
        val function = command?.let(GleoCommands::functionOf) ?: intent.action.orEmpty()
        respond(context, function, reply)
    }

    private fun respond(context: Context, function: String, reply: GleoReply) {
        val response = JSONObject()
            .put("functionName", function)
            .put("status", reply.status.wire)
            .put("message", reply.message)
            .put("mode", reply.mode.wire)
        context.sendBroadcast(
            Intent(ACTION_RESPONSE)
                .setPackage(GLEO_PACKAGE)
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString()),
        )
        EventLog.add("Gleo", "돌려준 결과: ${reply.status.wire} · ${reply.message}")
    }

    companion object {
        private const val EXTRA_PARAM = "ACTION_PARAM"
        private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        // 에뮬레이터에서 APP_ACTION_RESPONSE를 받는 곳으로 확인한 Gleo AI 패키지.
        private const val GLEO_PACKAGE = "ai.umos.ambientai"
    }
}
