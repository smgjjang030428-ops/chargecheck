package com.chargecheck.core.port

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.FailReason
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.ReviewTag
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    /** 남은 주행 가능 거리(km). 모르면 null. Pleos: Odometer.getRangeRemainingDistance (권한 CAR_DRIVE) */
    suspend fun remainingRangeKm(): Double? = null
}

interface DrivingStatePort {
    /** true = 주차/정차(화면 조작 허용), false = 주행(제한). Android CarUxRestrictions 기반. */
    fun isParked(): Flow<Boolean>
}

/** 내비 목적지·경유지. [remainM] = 내비가 알려 준 남은 도로 거리(m), 모르면 null. */
data class RouteTarget(val name: String, val location: GeoPoint, val poiId: String?, val remainM: Int? = null)

interface NaviPort {
    /** 기본 내비의 목적지·경유지 변화. */
    fun routeTargets(): Flow<List<RouteTarget>>
    /** 목적지·경유지 도착. */
    fun arrivals(): Flow<RouteTarget>
    suspend fun addWaypoint(target: RouteTarget): Result<Unit>
    /** 기본 내비에 목적지를 넘겨 길안내를 시작한다(지도 화면의 [여기로 안내]). Pleos: NaviHelper.requestRoute */
    suspend fun setDestination(target: RouteTarget): Result<Unit>
    /** 지금 목적지(경유지 제외). 없으면 null. 기본 구현은 routeTargets의 마지막(목적지가 맨 뒤). */
    fun destination(): Flow<RouteTarget?> = routeTargets().map { it.lastOrNull() }
    /** 기본 내비의 장소 검색 화면을 검색어와 함께 연다. Pleos: requestNavigationScreen(SearchPlace) */
    suspend fun openSearch(keyword: String): Result<Unit> = Result.failure(UnsupportedOperationException())
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
    /** 이미 올린 실패 기록에 운전자가 고른 충전기 번호를 붙인다. PATCH /v1/events/{eventId} */
    suspend fun attachCharger(eventId: String, chargerId: String): Result<Unit>
    /** 실패 기록의 충전기 번호·이유를 붙이거나 고친다(운전자가 다시 말한 경우). PATCH /v1/events/{eventId} */
    suspend fun updateEvent(eventId: String, chargerId: String?, reason: FailReason?): Result<Unit> =
        if (chargerId != null) attachCharger(eventId, chargerId) else Result.success(Unit)
    suspend fun stationStatus(id: StationId): Result<StationStatus>
    suspend fun nearbyStations(at: GeoPoint, radiusM: Int): Result<List<Station>>
    suspend fun alternatives(id: StationId, at: GeoPoint): Result<List<Pair<Station, StationStatus>>>
    suspend fun postReview(stationId: StationId, tags: List<ReviewTag>): Result<Unit>
}

/** 서버가 같은 기기·충전소 2시간 안 기록이라 받지 않았을 때(accepted=false, reason=DUPLICATE). */
class DuplicateEventException : IllegalStateException("DUPLICATE")

interface ClockPort {
    fun now(): Instant
}

/** 이벤트 ID 생성기. 테스트에서는 예측 가능한 값을 쓴다. */
fun interface IdFactory {
    fun newEventId(): String
}
