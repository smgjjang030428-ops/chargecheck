package com.chargecheck.core.model

import java.time.Instant

/** 공공 API의 충전소 ID(statId). */
@JvmInline
value class StationId(val value: String)

/** 앱 설치 시 생성한 임의 UUID. 서버에는 해시로만 저장된다. */
@JvmInline
value class DeviceId(val value: String)

data class GeoPoint(val lat: Double, val lon: Double)

enum class ChargerType { FAST, SLOW, UNKNOWN }

data class Station(
    val id: StationId,
    val name: String,
    val location: GeoPoint,
    /** 충전소 안에 급속이 하나라도 있으면 FAST. */
    val chargerType: ChargerType,
)

enum class Verdict { GREEN, YELLOW, RED, GRAY }

enum class ReasonCode {
    RECENT_SUCCESS,
    FAIL_AFTER_SUCCESS,
    /** 충전기 여러 대 중 일부에만 실패 기록. 나머지는 쓸 수 있으므로 도착 전 경고는 띄우지 않는다. */
    SOME_CHARGERS_FAIL,
    MULTI_FAIL,
    PUBLIC_OUT_OF_SERVICE,
    PUBLIC_SAYS_OK_BUT_FAILS,
    NO_RECENT_DATA,
}

enum class ReviewTag { CABLE_SHORT, SPACE_NARROW, HAS_ROOF, SLOW_SPEED, PAYMENT_ISSUE, BLOCKED_BY_CAR }

enum class PublicState { AVAILABLE, CHARGING, OUT_OF_SERVICE, UNKNOWN }

/** 충전기 한 대의 판정. chargerId는 공공 API의 chgerId("01", "02" …)와 같다. */
data class ChargerStatus(
    val chargerId: String,
    val chargerType: ChargerType,
    val publicState: PublicState,
    val verdict: Verdict,
    val recentFailCount: Int,
    /** 커넥터 방식. 예: ["DC콤보"], ["DC차데모", "AC3상"] */
    val connectors: List<String> = emptyList(),
    val outputKw: Int? = null,
)

/** 충전소 상세 정보(공공 API). 결제 수단은 공공 데이터에 없어 null이면 운영사로 안내한다. */
data class StationInfo(
    val address: String = "",
    val operator: String = "",
    val operatorCall: String = "",
    val useTime: String = "",
    val parkingFree: Boolean? = null,
    val limitDetail: String = "",
    val payment: String? = null,
)

/** 지도 화면 한 줄: 충전소 + 상세 정보 + 판정. */
data class NearbyStation(val station: Station, val info: StationInfo, val status: StationStatus)

data class StationStatus(
    val stationId: StationId,
    val verdict: Verdict,
    val reasonCode: ReasonCode,
    val lastSuccessAt: Instant?,
    /** 마지막 SUCCESS 이후 서로 다른 기기의 FAIL 수. */
    val recentFailCount: Int,
    /** 공공 API 기준 사용 가능 충전기 수. */
    val publicAvailable: Int?,
    val publicTotal: Int?,
    val topTags: List<ReviewTag>,
    /** 서버가 만든 운전자용 문장(예: "6대 중 1대에 최근 실패 기록"). */
    val message: String = "",
    /** 실패 판정·운영중지가 아닌 충전기 수. */
    val okCount: Int? = null,
    val chargers: List<ChargerStatus> = emptyList(),
)

enum class ChargeResult { SUCCESS, FAIL }

enum class DetectMode { LIVE, RETRO }

/** 서버로 보내는 충전 결과 1건. eventId는 서버 멱등성 키. */
data class ChargeEvent(
    val eventId: String,
    val deviceId: DeviceId,
    val stationId: StationId,
    val result: ChargeResult,
    val mode: DetectMode,
    val socStart: Int,
    val socEnd: Int,
    val minutes: Int,
    val occurredAt: Instant,
    /** 몇 번 충전기였는지. 차는 알 수 없으므로 보통 null이고, 실패 뒤 운전자가 고르면 attachCharger로 붙인다. */
    val chargerId: String? = null,
)

data class SocSample(val pct: Int, val at: Instant)

/**
 * 시동이 꺼져도 남는 도착 스냅샷(DataStore에 저장).
 * successSent는 같은 세션에서 SUCCESS를 두 번 보내지 않기 위해 함께 저장한다.
 */
data class ArrivalSnapshot(
    val stationId: StationId,
    val stationName: String,
    val chargerType: ChargerType,
    val soc: Int,
    val at: Instant,
    val location: GeoPoint?,
    val successSent: Boolean = false,
)
