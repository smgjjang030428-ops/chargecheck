package com.chargecheck.adapter.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.content.Context
import android.util.Log
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.port.ClockPort
import com.chargecheck.core.port.VehiclePort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "ChargeCheck/Pleos"

/**
 * Pleos Vehicle SDK로 구동 배터리 SOC를 읽는다. (기획문서/앱구조_명세서_AI용.md §4.14)
 * 권한: pleos.car.permission.CAR_ENERGY.
 * 주행 중 여부는 기어가 아니라 CarUxRestrictions로 판단한다(adapter:android의 CarUxRestrictionsAdapter).
 */
class PleosVehicleAdapter(context: Context, private val clock: ClockPort) : VehiclePort {

    private val vehicle = Vehicle(context).also { it.initialize() }
    private val battery get() = vehicle.getEvBattery()

    override fun soc(): Flow<SocSample> = callbackFlow {
        val listener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                Log.d(TAG, "SOC 이벤트: $value")
                if (value != null) trySend(SocSample(value, clock.now()))
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "SOC 구독 실패", e)
            }
        }
        battery.registerEvBatteryStateOfCharge(listener)
        // 등록 직후 현재 값도 한 번 흘려보낸다(값이 바뀌기 전까지 이벤트가 안 올 수 있음)
        currentSoc()?.let { trySend(SocSample(it, clock.now())) }
        awaitClose { battery.unregisterEvBatteryStateOfCharge(listener) }
    }.distinctUntilChanged { a, b -> a.pct == b.pct }

    override suspend fun currentSoc(): Int? = suspendCancellableCoroutine { cont ->
        battery.getEvBatteryStateOfCharge(
            { value -> if (cont.isActive) cont.resume(value) },
            { e ->
                Log.w(TAG, "SOC 읽기 실패", e)
                if (cont.isActive) cont.resume(null)
            },
        )
    }

    /** 목표 충전량을 읽는 API가 Vehicle SDK 2.3.2에는 없다(설정·지원 범위만 있음). ⚠️ */
    override suspend fun chargeTargetPct(): Int? = null

    fun release() = vehicle.release()
}
