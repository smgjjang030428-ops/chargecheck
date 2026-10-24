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

/** 충전 세션의 상태. 문서/앱구조_명세서_AI용.md §4.7 상태도와 1:1로 대응한다. */
sealed interface SessionState {
    data object Idle : SessionState

    /** 내비 목적지가 충전소. */
    data class Approaching(val station: Station) : SessionState

    /** 충전소 근처에 정차. 최소 정차 시간을 채우면 Monitoring. */
    data class Candidate(
        val station: Station,
        val stoppedAt: Instant,
        val lastSoc: SocSample? = null,
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

    /** 내비 없이 충전소 반경(onsiteRadiusM) 안에 정차. */
    data class StoppedNearStation(val station: Station, val at: Instant) : SessionInput

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
}

data class Transition(val state: SessionState, val effects: List<Effect> = emptyList())
