package com.chargecheck.core.port

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.ReviewTag
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * 기능 코드가 바깥 세상(Pleos SDK, Android API, 서버)을 부르는 유일한 통로.
 * 구현체는 adapter 모듈에만 있다. feature 모듈은 이 인터페이스만 안다.
 */
interface VehiclePort {
    /** 배터리 잔량(%) 변화 스트림. Pleos: EvBattery.registerEvBatteryStateOfCharge */
    fun soc(): Flow<SocSample>
    suspend fun currentSoc(): Int?
    /** 목표 충전량(%). 없으면 null. ⚠️ UNVERIFIED: Pleos에서 읽기 API 존재 여부 */
    suspend fun chargeTargetPct(): Int?
}

interface DrivingStatePort {
    /** true = 주차/정차(화면 조작 허용), false = 주행(제한). Android CarUxRestrictions 기반. */
    fun isParked(): Flow<Boolean>
}

data class RouteTarget(val name: String, val location: GeoPoint, val poiId: String?)

interface NaviPort {
    /** 기본 내비의 목적지·경유지 변화. */
    fun routeTargets(): Flow<List<RouteTarget>>
    /** 목적지·경유지 도착. */
    fun arrivals(): Flow<RouteTarget>
    suspend fun addWaypoint(target: RouteTarget): Result<Unit>
}

interface LocationPort {
    fun locations(): Flow<GeoPoint>
    suspend fun lastKnown(): GeoPoint?
}

enum class VoiceIntent { YES, WAIT, NO, SWITCH, KEEP }

interface VoicePort {
    suspend fun say(text: String)
    /** 인식 결과를 의도로 분류한다. 타임아웃·오류는 null(= 무응답). */
    suspend fun ask(question: String, timeoutSec: Int): VoiceIntent?
}

interface ChargeCheckApiPort {
    suspend fun postEvent(event: ChargeEvent): Result<Unit>
    suspend fun stationStatus(id: StationId): Result<StationStatus>
    suspend fun nearbyStations(at: GeoPoint, radiusM: Int): Result<List<Station>>
    suspend fun alternatives(id: StationId, at: GeoPoint): Result<List<Pair<Station, StationStatus>>>
    suspend fun postReview(stationId: StationId, tags: List<ReviewTag>): Result<Unit>
}

interface ClockPort {
    fun now(): Instant
}

/** 이벤트 ID 생성기. 테스트에서는 예측 가능한 값을 쓴다. */
fun interface IdFactory {
    fun newEventId(): String
}
