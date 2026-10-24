package com.chargecheck.adapter.android

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.chargecheck.core.port.DrivingStatePort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val TAG = "ChargeCheck/UxR"

/**
 * 주행 규제 상태. Pleos 주행 규제 개발 가이드대로 CarUxRestrictions를 기준으로 판단한다.
 * isRequiresDistractionOptimization() == true 이면 주행 중(제한 화면), false 이면 주차·정차(전체 화면).
 * 앱 액티비티는 distractionOptimized로 선언돼 있어야 주행 중에도 화면이 보인다(AndroidManifest).
 */
class CarUxRestrictionsAdapter(private val context: Context) : DrivingStatePort {

    override fun isParked(): Flow<Boolean> = callbackFlow {
        val car = Car.createCar(context)
        val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        if (manager == null) {
            Log.w(TAG, "CarUxRestrictionsManager 없음 → 주차로 간주")
            trySend(true)
        } else {
            fun emit(r: CarUxRestrictions) {
                Log.d(TAG, "주행 규제: DO 필요=${r.isRequiresDistractionOptimization} 제한=${r.activeRestrictions}")
                trySend(!r.isRequiresDistractionOptimization)
            }
            emit(manager.currentCarUxRestrictions)
            manager.registerListener { emit(it) }
        }
        awaitClose {
            manager?.unregisterListener()
            car?.disconnect()
        }
    }.distinctUntilChanged()
}
