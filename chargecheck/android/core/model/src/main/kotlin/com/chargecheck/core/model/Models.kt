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
    MULTI_FAIL,
    PUBLIC_OUT_OF_SERVICE,
    PUBLIC_SAYS_OK_BUT_FAILS,
    NO_RECENT_DATA,
}

enum class ReviewTag { CABLE_SHORT, SPACE_NARROW, HAS_ROOF, SLOW_SPEED, PAYMENT_ISSUE, BLOCKED_BY_CAR }

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
