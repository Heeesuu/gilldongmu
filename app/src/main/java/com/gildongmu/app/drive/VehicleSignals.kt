package com.gildongmu.app.drive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gildongmu.app.EventLog

/**
 * 녹음할 때 함께 저장할 차량 상황 (Pleos Vehicle State): 와이퍼·전조등.
 * 2차에서 비 오는 날·밤에 맞는 한마디를 먼저 들려줄 때 쓴다. 값을 모르면 null이고, 업로드 때 생략한다.
 */
class VehicleSignals(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var wiperOn: Boolean? = null
        private set

    @Volatile
    var headlightOn: Boolean? = null
        private set

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            wiperOn = wiperOnOf(state)
        }

        override fun onFailed(e: Exception) = fail("와이퍼", e)
    }

    private val headlightListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            headlightOn = headlightOnOf(state)
        }

        override fun onFailed(e: Exception) = fail("전조등", e)
    }

    fun start() {
        vehicle.initialize()
        // initialize()가 끝나도 서비스 연결은 보장되지 않아서, 잠시 뒤에 값을 읽고 구독한다 (에뮬레이터 확인 결과).
        handler.postDelayed({ subscribe() }, 1_500L)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getWiper().unregisterWiperState(frontWindshield, wiperListener)
            vehicle.getLight().unregisterLowBeamLightState(headlightListener)
        }
        vehicle.release()
    }

    private fun subscribe() {
        val wiper = vehicle.getWiper()
        wiper.getCurrentWiperState(frontWindshield, { wiperOn = wiperOnOf(it) }, { fail("와이퍼", it) })
        wiper.registerWiperState(frontWindshield, wiperListener)

        val light = vehicle.getLight()
        light.getCurrentLowBeamLightState({ headlightOn = headlightOnOf(it) }, { fail("전조등", it) })
        light.registerLowBeamLightState(headlightListener)
    }

    private fun fail(label: String, e: Exception) {
        EventLog.add("차량", "$label 읽기 실패: ${e.javaClass.simpleName} ${e.message}")
    }

    // 꺼짐이 아닌 와이퍼 상태(자동·간헐 등)는 비가 오는 것으로 본다.
    private fun wiperOnOf(state: WiperState?) = state?.let { it != WiperState.OFF }

    // 주간주행등은 전조등을 켠 것으로 보지 않는다.
    private fun headlightOnOf(state: LightState?) = state?.let { it == LightState.ON }
}
