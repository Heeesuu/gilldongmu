package com.gildongmu.app.drive

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import com.gildongmu.app.EventLog

/**
 * 지금 주행 중인지 (AAOS 표준 CarUxRestrictions).
 * 주행 중에는 소리와 큰 버튼만, 정차 중에는 내 꽃밭·설정까지 보여준다 (기획서 3·5번).
 * 에뮬레이터에서는 속도가 0이어도 기어가 D이면 바로 주행 중(영상·설정 화면 금지)으로 바뀐다.
 * Car 서비스에 연결되기 전에는 정차 중으로 본다.
 */
class DrivingMode(private val context: Context, private val onChange: (driving: Boolean) -> Unit) {

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    @Volatile
    var driving = false
        private set

    fun connect() {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (ready) {
                val m = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                manager = m
                update(m.currentCarUxRestrictions, force = true)
                m.registerListener { update(it) }
            } else {
                manager = null
            }
        }
    }

    fun disconnect() {
        manager?.unregisterListener()
        manager = null
        car?.disconnect()
        car = null
    }

    private fun update(restrictions: CarUxRestrictions?, force: Boolean = false) {
        val now = restrictions?.isRequiresDistractionOptimization ?: false
        if (now == driving && !force) return
        driving = now
        EventLog.add("주행", if (now) "주행 중 화면으로 바꿔요" else "정차 중 화면으로 바꿔요")
        onChange(now)
    }
}
