package com.chargecheck.adapter.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
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
 * Pleos Vehicle SDK로 구동 배터리 SOC를 읽는다. (기획문서/차지체크_앱_구동_기획.md §3)
 * 권한: pleos.car.permission.CAR_ENERGY(SOC), pleos.car.permission.CAR_DRIVE(남은 주행 가능 거리).
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

    /** 충전구 열림·닫힘(권한 CAR_ENERGY). 내비 없이 충전소에 온 차가 '충전하려 했다'는 증거로 쓴다. */
    override fun chargePortOpen(): Flow<Boolean> = callbackFlow {
        val listener = object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(value: Boolean?) {
                Log.d(TAG, "충전구 이벤트: $value")
                if (value != null) trySend(value)
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "충전구 구독 실패", e)
            }
        }
        battery.registerEvChargePortOpen(listener)
        battery.isEvChargePortOpen({ value -> if (value != null) trySend(value) }, { e -> Log.w(TAG, "충전구 읽기 실패", e) })
        awaitClose { battery.unregisterEvChargePortOpen(listener) }
    }.distinctUntilChanged()

    /** 목표 충전량을 읽는 API가 Vehicle SDK 2.3.2에는 없다(설정·지원 범위만 있음). ⚠️ */
    override suspend fun chargeTargetPct(): Int? = null

    /**
     * 남은 주행 가능 거리. 단위는 미터로 정한다: Android VHAL RANGE_REMAINING이 미터이고, Pleos 에뮬레이터에서도 미터로 왔다.
     * 값 크기로 단위를 추측하지 않는다(m로 1,500이 오면 1,500km로 읽게 된다).
     * 미터로 읽어 [MAX_RANGE_KM]를 넘는 값은 단위가 다르다는 뜻이므로 모르는 것(null)으로 둔다. ⚠️ 실차 단위는 설명회에서 확인.
     */
    override suspend fun remainingRangeKm(): Double? = suspendCancellableCoroutine { cont ->
        vehicle.getOdometer().getRangeRemainingDistance(
            { value ->
                Log.d(TAG, "남은 주행 가능 거리 원값: $value")
                val km = value?.toDouble()?.div(METERS_PER_KM)
                if (cont.isActive) cont.resume(km?.takeIf { it > 0 && it <= MAX_RANGE_KM })
            },
            { e ->
                Log.w(TAG, "남은 주행 가능 거리 읽기 실패", e)
                if (cont.isActive) cont.resume(null)
            },
        )
    }

    fun release() = vehicle.release()
}

private const val METERS_PER_KM = 1000.0

/** 승용 전기차가 한 번 충전으로 갈 수 있는 거리의 넉넉한 상한. 이보다 크면 단위가 잘못 들어온 값이다. */
private const val MAX_RANGE_KM = 1_200.0
