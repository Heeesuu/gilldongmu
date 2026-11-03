package com.gildongmu.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * 확인 항목: Pleos SDK로 위치·진행 방향·다음 회전(NaviHelper)과 와이퍼·전조등·배터리(Vehicle State)를 받는지.
 * NaviHelper의 get 함수는 값을 바로 돌려주지 않고 리스너로 보내므로 2초마다 다시 요청한다.
 */
class PleosCheck(context: Context) {

    private val naviHelper = NaviHelper(context.applicationContext)
    private val vehicle = Vehicle(context.applicationContext)
    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val handler = Handler(Looper.getMainLooper())
    private var naviConnected = false

    @Volatile private var naviStatus = "연결 전"
    @Volatile private var location = "-"
    @Volatile private var nextTurn = "-"
    @Volatile private var wiper = "-"
    @Volatile private var headlight = "-"
    @Volatile private var battery = "-"

    fun summary() =
        "NaviHelper: $naviStatus\n" +
            "현재 위치: $location\n" +
            "다음 회전: $nextTurn\n" +
            "Vehicle State · 와이퍼: $wiper · 전조등: $headlight · 배터리: $battery"

    private val poll = object : Runnable {
        override fun run() {
            if (naviConnected) {
                naviHelper.getCurrentLocationInfo()
                naviHelper.getTBTInfo()
            }
            handler.postDelayed(this, 2_000L)
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val text = "%.6f, %.6f · 진행 방향 %.0f° · %s".format(
                Locale.KOREA,
                currentLocationInfo.latitude,
                currentLocationInfo.longitude,
                currentLocationInfo.heading,
                currentLocationInfo.address,
            )
            if (text != location) EventLog.add("NaviHelper", "위치 → $text")
            location = text
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val text = tbtInfoList.firstOrNull()
                ?.let { "${it.type} · ${it.distance.toInt()}m · ${it.description}" }
                ?: "없음 (경로 안내 중이 아님)"
            if (text != nextTurn) EventLog.add("NaviHelper", "다음 회전 → $text")
            nextTurn = text
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            naviStatus = "연결됨 · 내비 상태 $status"
            EventLog.add("NaviHelper", "내비 상태 → $status")
        }

        override fun onError(errorCode: NaviErrorCode) {
            EventLog.add("NaviHelper", "오류 → $errorCode")
        }
    }

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            wiper = wiperName(state)
            EventLog.add("Vehicle", "와이퍼 → $wiper")
        }

        override fun onFailed(e: Exception) {
            wiper = "구독 실패: ${e.message}"
            EventLog.add("Vehicle", wiper)
        }
    }

    private val headlightListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            headlight = lightName(state)
            EventLog.add("Vehicle", "전조등 → $headlight")
        }

        override fun onFailed(e: Exception) {
            headlight = "구독 실패: ${e.message}"
            EventLog.add("Vehicle", headlight)
        }
    }

    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(stateOfCharge: Int?) {
            battery = "${stateOfCharge ?: "-"}%"
            EventLog.add("Vehicle", "배터리 → $battery")
        }

        override fun onFailed(e: Exception) {
            battery = "구독 실패: ${e.message}"
            EventLog.add("Vehicle", battery)
        }
    }

    fun start() {
        naviHelper.initialize(
            {
                naviConnected = true
                naviStatus = "연결됨"
                EventLog.add("NaviHelper", "연결됨")
                naviHelper.addListener(naviListener)
            },
            { error ->
                naviStatus = "연결 실패: ${error.message}"
                EventLog.add("NaviHelper", naviStatus)
            },
        )
        handler.post(poll)

        // initialize()가 끝나도 서비스 연결은 보장되지 않아서, 잠시 뒤에 값을 읽고 구독한다.
        vehicle.initialize()
        handler.postDelayed({ readVehicle() }, 1_500L)
    }

    /** 처음 값을 읽고 변경 알림을 구독한다. 화면의 '다시 읽기' 버튼에서도 부른다. */
    fun readVehicle() {
        val wiperApi = vehicle.getWiper()
        wiperApi.getCurrentWiperState(frontWindshield, { state -> wiper = wiperName(state) }, { e -> fail("와이퍼", e) })
        wiperApi.unregisterWiperState(frontWindshield, wiperListener)
        wiperApi.registerWiperState(frontWindshield, wiperListener)

        val lightApi = vehicle.getLight()
        lightApi.getCurrentLowBeamLightState({ state -> headlight = lightName(state) }, { e -> fail("전조등", e) })
        lightApi.unregisterLowBeamLightState(headlightListener)
        lightApi.registerLowBeamLightState(headlightListener)

        val batteryApi = vehicle.getEvBattery()
        batteryApi.getEvBatteryStateOfCharge({ soc -> battery = "${soc ?: "-"}%" }, { e -> fail("배터리", e) })
        batteryApi.unregisterEvBatteryStateOfCharge(batteryListener)
        batteryApi.registerEvBatteryStateOfCharge(batteryListener)
        EventLog.add("Vehicle", "와이퍼·전조등·배터리 읽기 요청")
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        naviHelper.removeListener(naviListener)
        naviHelper.release()
        vehicle.getWiper().unregisterWiperState(frontWindshield, wiperListener)
        vehicle.getLight().unregisterLowBeamLightState(headlightListener)
        vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(batteryListener)
        vehicle.release()
    }

    private fun fail(label: String, e: Exception) {
        EventLog.add("Vehicle", "$label 읽기 실패: ${e.javaClass.simpleName} ${e.message}")
    }

    private fun wiperName(state: WiperState?) = when (state) {
        WiperState.ON -> "켜짐"
        WiperState.OFF -> "꺼짐"
        null -> "-"
        else -> state.toString()
    }

    private fun lightName(state: LightState?) = when (state) {
        LightState.ON -> "켜짐"
        LightState.OFF -> "꺼짐"
        LightState.DAYTIME_RUNNING -> "주간주행등"
        null -> "-"
        else -> state.toString()
    }
}
