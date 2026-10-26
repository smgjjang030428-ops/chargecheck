package com.chargecheck.feature.session

import com.chargecheck.core.model.ArrivalSnapshot
import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.port.VoiceIntent
import java.time.Instant

/** 충전 세션의 상태. 기획문서/차지체크_앱_구동_기획.md §5 상태도와 1:1로 대응한다. */
sealed interface SessionState {
    data object Idle : SessionState

    /** 내비 목적지가 충전소. */
    data class Approaching(val station: Station) : SessionState

    /** 충전소 근처에 정차. 최소 정차 시간을 채우면 Monitoring. */
    data class Candidate(
        val station: Station,
        val stoppedAt: Instant,
        val lastSoc: SocSample? = null,
        /** 현장으로 보는 반경. null이면 onsiteRadiusM(내비 도착). 충전구로 시작하면 portRadiusM. */
        val radiusM: Int? = null,
    ) : SessionState

    /** SOC를 실시간으로 지켜보는 중(LIVE). */
    data class Monitoring(
        val snap: ArrivalSnapshot,
        val lastSoc: SocSample,
        val stallDeadline: Instant,
    ) : SessionState

    /** "충전이 안 되나요?" 질문 중. */
    data class Asking(val snap: ArrivalSnapshot, val lastSoc: SocSample) : SessionState

    /** 충전 확인됨. 이 상태에 들어왔다면 SUCCESS는 이미 업로드됐다(snap.successSent = true). */
    data class Charging(val snap: ArrivalSnapshot, val lastSoc: SocSample) : SessionState

    data class Completed(val snap: ArrivalSnapshot) : SessionState

    data class Failed(val snap: ArrivalSnapshot) : SessionState

    /** 차량(앱)이 꺼진 상태. 스냅샷만 남아 있다. */
    data class Suspended(val snap: ArrivalSnapshot) : SessionState

    /** 다시 켜졌는데 SOC가 그대로라 과거형으로 질문 중(RETRO). */
    data class AskingRetro(
        val snap: ArrivalSnapshot,
        val currentSoc: Int,
        val resumedAt: Instant,
    ) : SessionState

    /** 충전소를 떠남. 주차 중이면 UI가 리뷰를 띄운다. */
    data class Ended(val snap: ArrivalSnapshot, val result: ChargeResult?) : SessionState
}

/** 상태 머신에 들어오는 입력. 모두 SessionCoordinator가 포트 이벤트를 변환해 만든다. */
sealed interface SessionInput {
    data class RouteTargetIsStation(val station: Station) : SessionInput

    /** 내비 없이 충전소 근처에 정차해 충전구를 열었다. [radiusM] 안이면 현장으로 본다. */
    data class StoppedNearStation(val station: Station, val at: Instant, val radiusM: Int? = null) : SessionInput

    /** 내비 목적지·경유지 도착. */
    data class Arrived(val station: Station, val at: Instant) : SessionInput

    /** 세션 충전소로부터의 현재 거리. */
    data class Moved(val distanceFromStationM: Int) : SessionInput

    data class Soc(val sample: SocSample) : SessionInput

    data class Tick(val now: Instant) : SessionInput

    /** 음성 또는 버튼 응답. intent = null 은 무응답(타임아웃·인식 실패). */
    data class Answer(val intent: VoiceIntent?, val at: Instant) : SessionInput

    /** 앱 프로세스가 멈춤(시동 꺼짐 등). */
    data object AppStopped : SessionInput

    /** 앱이 다시 켜짐. 스냅샷이 있으면 Coordinator가 Suspended 상태에서 이 입력을 넣는다. */
    data class AppResumed(val soc: Int?, val location: GeoPoint?, val now: Instant) : SessionInput

    data class TargetReached(val pct: Int) : SessionInput

    /** 주차 → 주행으로 바뀜(기어를 P에서 뺌). 충전소에서 잔량 변화 없이 출발하는지 보려고 쓴다. */
    data class Departing(val at: Instant) : SessionInput

    /** 리뷰를 마치거나 건너뜀. */
    data object ReviewDone : SessionInput
}

/** 상태 머신이 요청하는 부수 효과. 실행은 SessionCoordinator가 한다. */
sealed interface Effect {
    data class SaveSnapshot(val snap: ArrivalSnapshot) : Effect
    data object ClearSnapshot : Effect
    data class Upload(val event: ChargeEvent) : Effect
    data class Ask(val question: String, val retro: Boolean) : Effect
    data class Say(val text: String) : Effect
    data class ShowToast(val text: String) : Effect
    data class ScheduleTick(val at: Instant) : Effect
    data object NotifyComplete : Effect
    data class OfferReview(val stationId: StationId) : Effect

    /**
     * 충전소에서 잔량 변화 없이 떠남 → "충전이 안 됐나요?"를 운전자에게 확인한다.
     * [event]는 미리 만든 FAIL 기록이고, 운전자가 YES라고 할 때만 올린다(무응답은 기록하지 않음).
     * 세션과 따로 SessionCoordinator가 들고 있다가, 출발 직후(주행 화면)와 다음 주차 때 한 번씩 묻는다.
     */
    data class ConfirmLater(val event: ChargeEvent, val stationName: String) : Effect
}

data class Transition(val state: SessionState, val effects: List<Effect> = emptyList())
