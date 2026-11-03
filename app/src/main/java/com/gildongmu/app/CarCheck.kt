package com.gildongmu.app

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import java.util.Locale

/**
 * 확인 항목: AAOS 표준 Car API로 차량 속도·기어를 읽을 권한이 있는지, 주행 제한 상태를 받는지.
 * Pleos Vehicle SDK에는 속도 API가 없어서(2.3.2에서 제거) 표준 API가 유일한 경로다.
 */
class CarCheck(private val context: Context) {

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var subscribed = false

    @Volatile private var status = "Car 서비스 연결 전"
    @Volatile private var speed = "-"
    @Volatile private var gear = "-"
    @Volatile private var uxRestrictions = "-"

    fun summary() = "$status\n차량 속도: $speed · 기어: $gear\n주행 제한(UX Restrictions): $uxRestrictions"

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    speed = "%.1f km/h".format(Locale.KOREA, (value.value as Float) * 3.6f)
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val name = gearName(value.value as Int)
                    if (name != gear) EventLog.add("차량", "기어 → $name")
                    gear = name
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            EventLog.add("차량", "${VehiclePropertyIds.toString(propertyId)} 오류 이벤트")
        }
    }

    fun connect() {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (ready) {
                onCarReady(connected)
            } else {
                status = "Car 서비스 연결 끊김"
                propertyManager = null
                subscribed = false
            }
        }
    }

    fun disconnect() {
        propertyManager?.unregisterCallback(propertyCallback)
        car?.disconnect()
        car = null
    }

    /** 권한을 받은 뒤 다시 불러도 되도록 한 번만 구독한다. */
    fun subscribe() {
        val manager = propertyManager ?: return
        if (subscribed) return
        subscribed = true
        register(manager, VehiclePropertyIds.PERF_VEHICLE_SPEED, "차량 속도", CarPropertyManager.SENSOR_RATE_NORMAL) { speed = it }
        register(manager, VehiclePropertyIds.GEAR_SELECTION, "기어", CarPropertyManager.SENSOR_RATE_ONCHANGE) { gear = it }
    }

    private fun onCarReady(connected: Car) {
        status = "Car 서비스 연결됨"
        EventLog.add("차량", status)
        watchUxRestrictions(connected)
        propertyManager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        subscribe()
    }

    private fun register(manager: CarPropertyManager, propertyId: Int, label: String, rate: Float, onFail: (String) -> Unit) {
        val result = try {
            if (manager.registerCallback(propertyCallback, propertyId, rate)) "구독 성공" else "구독 실패(지원 안 함)"
        } catch (e: SecurityException) {
            "권한 거부: ${e.message}"
        } catch (e: IllegalArgumentException) {
            "지원하지 않는 속성: ${e.message}"
        }
        EventLog.add("차량", "$label → $result")
        if (result != "구독 성공") onFail(result)
    }

    private fun watchUxRestrictions(connected: Car) {
        val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        uxRestrictions = describe(manager.currentCarUxRestrictions)
        EventLog.add("차량", "주행 제한 → $uxRestrictions")
        manager.registerListener { restrictions ->
            uxRestrictions = describe(restrictions)
            EventLog.add("차량", "주행 제한 바뀜 → $uxRestrictions")
        }
    }

    private fun describe(restrictions: CarUxRestrictions?): String = when {
        restrictions == null -> "알 수 없음"
        restrictions.isRequiresDistractionOptimization ->
            "주행 중 제한 적용 (코드 0x${Integer.toHexString(restrictions.activeRestrictions)})"
        else -> "제한 없음"
    }

    // VehicleGear 값: N=1, R=2, P=4, D=8
    private fun gearName(value: Int) = when (value) {
        1 -> "N"
        2 -> "R"
        4 -> "P"
        8 -> "D"
        else -> "기타($value)"
    }
}
