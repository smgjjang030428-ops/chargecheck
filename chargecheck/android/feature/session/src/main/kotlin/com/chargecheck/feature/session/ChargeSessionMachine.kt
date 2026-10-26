package com.chargecheck.feature.session

import com.chargecheck.core.config.ChargeCheckConfig
import com.chargecheck.core.model.ArrivalSnapshot
import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.DetectMode
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.FailReason
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.IdFactory
import com.chargecheck.core.port.VoiceIntent
import com.chargecheck.feature.session.SessionInput.Answer
import com.chargecheck.feature.session.SessionInput.AppResumed
import com.chargecheck.feature.session.SessionInput.AppStopped
import com.chargecheck.feature.session.SessionInput.Arrived
import com.chargecheck.feature.session.SessionInput.Departing
import com.chargecheck.feature.session.SessionInput.Moved
import com.chargecheck.feature.session.SessionInput.ReviewDone
import com.chargecheck.feature.session.SessionInput.RouteTargetIsStation
import com.chargecheck.feature.session.SessionInput.Soc
import com.chargecheck.feature.session.SessionInput.StoppedNearStation
import com.chargecheck.feature.session.SessionInput.TargetReached
import com.chargecheck.feature.session.SessionInput.Tick
import com.chargecheck.feature.session.SessionState.Approaching
import com.chargecheck.feature.session.SessionState.Asking
import com.chargecheck.feature.session.SessionState.AskingRetro
import com.chargecheck.feature.session.SessionState.Candidate
import com.chargecheck.feature.session.SessionState.Charging
import com.chargecheck.feature.session.SessionState.Completed
import com.chargecheck.feature.session.SessionState.Ended
import com.chargecheck.feature.session.SessionState.Failed
import com.chargecheck.feature.session.SessionState.Idle
import com.chargecheck.feature.session.SessionState.Monitoring
import com.chargecheck.feature.session.SessionState.Suspended
import java.time.Duration
import java.time.Instant

/**
 * 충전 세션 판단 로직. 부수 효과 없는 순수 함수다.
 * 전이 번호(T1…)는 기획문서/차지체크_앱_구동_기획.md §5 전이표와 같다.
 *
 * 원칙
 * - SUCCESS는 자동으로, FAIL은 운전자 확인(YES) 뒤에만 업로드한다.
 * - 무응답은 절대 FAIL로 기록하지 않는다.
 * - 한 세션에서 SUCCESS는 한 번만 업로드한다(snap.successSent).
 * - 잔량 변화 없이 충전소를 떠나면 세션을 그냥 지우지 않고 [Effect.ConfirmLater]로 운전자 확인을 맡긴다(T22~T25).
 */
class ChargeSessionMachine(
    private val deviceId: DeviceId,
    private val ids: IdFactory,
    internal val cfg: ChargeCheckConfig = ChargeCheckConfig.DEFAULT,
) {

    /** 운전자가 상세 카드에서 직접 [여기 충전 안 됐어요]를 누른 기록(F2 보조). 현장에 있었는지는 호출하는 쪽이 확인한다. */
    fun manualFail(station: Station, soc: Int?, at: Instant, reason: FailReason? = null, chargerId: String? = null) = ChargeEvent(
        eventId = ids.newEventId(),
        deviceId = deviceId,
        stationId = station.id,
        result = ChargeResult.FAIL,
        mode = DetectMode.MANUAL,
        socStart = soc ?: 0,
        socEnd = soc ?: 0,
        minutes = 0,
        occurredAt = at,
        chargerId = chargerId,
        reason = reason,
    )

    fun reduce(state: SessionState, input: SessionInput): Transition = when (state) {
        Idle -> onIdle(input)
        is Approaching -> onApproaching(state, input)
        is Candidate -> onCandidate(state, input)
        is Monitoring -> onMonitoring(state, input)
        is Asking -> onAsking(state, input)
        is Charging -> onCharging(state, input)
        is Completed -> onCompleted(state, input)
        is Failed -> onFailed(state, input)
        is Suspended -> onSuspended(state, input)
        is AskingRetro -> onAskingRetro(state, input)
        is Ended -> onEnded(input)
    }

    // ---------------------------------------------------------------- 상태별 처리

    private fun onIdle(input: SessionInput): Transition = when (input) {
        is RouteTargetIsStation -> Transition(Approaching(input.station)) // T1
        is StoppedNearStation -> startCandidate(input.station, input.at) // T2
        is Arrived -> startCandidate(input.station, input.at) // T2
        else -> stay(Idle)
    }

    private fun onApproaching(state: Approaching, input: SessionInput): Transition = when (input) {
        is RouteTargetIsStation -> Transition(Approaching(input.station))
        is StoppedNearStation -> startCandidate(input.station, input.at) // T2
        is Arrived -> startCandidate(input.station, input.at) // T2
        else -> stay(state)
    }

    private fun onCandidate(state: Candidate, input: SessionInput): Transition = when (input) {
        is Soc -> Transition(state.copy(lastSoc = input.sample))
        is Tick -> {
            val readyAt = state.stoppedAt + cfg.onsiteMinStop
            val soc = state.lastSoc
            when {
                input.now < readyAt -> stay(state)
                soc == null -> Transition(state, listOf(Effect.ScheduleTick(input.now + cfg.socRetryInterval)))
                else -> startMonitoring(state.station, state.stoppedAt, soc, input.now) // T3
            }
        }
        is Moved -> if (input.distanceFromStationM > cfg.onsiteRadiusM) Transition(Idle) else stay(state) // T4
        is Departing -> { // T23: 세션 시작(onsiteMinStop) 전에 출발. attemptMinStop 넘게 있었으면 꽂아 보고 떠났을 수 있다
            val soc = state.lastSoc
            if (soc != null && Duration.between(state.stoppedAt, input.at) >= cfg.attemptMinStop) {
                Transition(Idle, listOf(confirm(snapshot(state.station, state.stoppedAt, soc), DetectMode.LIVE, soc.pct, input.at)))
            } else {
                Transition(Idle)
            }
        }
        AppStopped -> Transition(Idle)
        else -> stay(state)
    }

    private fun onMonitoring(state: Monitoring, input: SessionInput): Transition = when (input) {
        is Soc ->
            if (isLiveSuccess(state.snap, input.sample)) {
                liveSuccess(state.snap, input.sample) // T5
            } else {
                Transition(state.copy(lastSoc = input.sample))
            }
        is Tick ->
            if (input.now >= state.stallDeadline) {
                Transition(Asking(state.snap, state.lastSoc), listOf(Effect.Ask(QUESTION_LIVE, retro = false))) // T6
            } else {
                stay(state)
            }
        AppStopped -> Transition(Suspended(state.snap)) // T10
        is Departing -> departedWithoutCharge(state.snap, DetectMode.LIVE, state.lastSoc.pct, input.at) // T24
        is Moved -> leftWithoutResult(state, state.snap, DetectMode.LIVE, state.lastSoc.pct, input) // T22
        else -> stay(state)
    }

    private fun onAsking(state: Asking, input: SessionInput): Transition = when (input) {
        is Answer -> when (input.intent) {
            VoiceIntent.YES -> Transition( // T7
                Failed(state.snap),
                listOf(
                    Effect.Upload(event(state.snap, ChargeResult.FAIL, DetectMode.LIVE, state.lastSoc.pct, input.at)),
                    Effect.ClearSnapshot,
                    Effect.Say(SAY_FAIL_RECORDED),
                ),
            )
            VoiceIntent.WAIT -> { // T8
                val deadline = input.at + cfg.waitExtension
                Transition(Monitoring(state.snap, state.lastSoc, deadline), listOf(Effect.ScheduleTick(deadline)))
            }
            else -> Transition(Idle, listOf(Effect.ClearSnapshot)) // T9 (NO 또는 무응답)
        }
        is Soc -> if (isLiveSuccess(state.snap, input.sample)) liveSuccess(state.snap, input.sample) else stay(state) // T21
        AppStopped -> Transition(Suspended(state.snap)) // T10
        // T24: 답하지 않고 출발 → 질문을 주행 화면 확인 카드로 넘긴다
        is Departing -> departedWithoutCharge(state.snap, DetectMode.LIVE, state.lastSoc.pct, input.at)
        is Moved -> leftWithoutResult(state, state.snap, DetectMode.LIVE, state.lastSoc.pct, input) // T22
        else -> stay(state)
    }

    private fun onCharging(state: Charging, input: SessionInput): Transition = when (input) {
        is Soc -> Transition(state.copy(lastSoc = input.sample)) // T16
        is TargetReached -> Transition(Completed(state.snap), listOf(Effect.NotifyComplete)) // T17
        AppStopped -> Transition(Suspended(state.snap)) // T10 (successSent = true 유지)
        is Moved -> leave(state.snap, ChargeResult.SUCCESS, input) ?: stay(state) // T18
        else -> stay(state)
    }

    private fun onCompleted(state: Completed, input: SessionInput): Transition = when (input) {
        AppStopped -> Transition(Suspended(state.snap))
        is Moved -> leave(state.snap, ChargeResult.SUCCESS, input) ?: stay(state) // T18
        else -> stay(state)
    }

    private fun onFailed(state: Failed, input: SessionInput): Transition = when (input) {
        AppStopped -> Transition(Idle) // 스냅샷은 실패 기록 때 이미 지웠다
        is Moved -> leave(state.snap, ChargeResult.FAIL, input) ?: stay(state) // T18
        else -> stay(state)
    }

    private fun onSuspended(state: Suspended, input: SessionInput): Transition {
        if (input !is AppResumed) return stay(state)
        val snap = state.snap
        val soc = input.soc
        val elapsed = Duration.between(snap.at, input.now)
        val here = input.location
        val there = snap.location
        val movedAway = here != null && there != null && here.distanceMetersTo(there) > cfg.leftRadiusM

        // T13a: 만료·이동·SOC 미상 → 기록 없이 종료
        if (soc == null || elapsed > cfg.retroMaxElapsed || movedAway) {
            return Transition(Idle, listOf(Effect.ClearSnapshot))
        }
        val sample = SocSample(soc, input.now)

        // T20: 이미 SUCCESS를 보낸 세션이면 다시 보내지 않는다
        if (snap.successSent) return Transition(Charging(snap, sample))

        val delta = soc - snap.soc
        return when {
            // T11: 꺼진 동안 충전됨
            delta >= cfg.successDeltaRetroPct -> {
                val sent = snap.copy(successSent = true)
                Transition(
                    Charging(sent, sample),
                    listOf(
                        Effect.Upload(event(snap, ChargeResult.SUCCESS, DetectMode.RETRO, soc, input.now)),
                        Effect.SaveSnapshot(sent),
                        Effect.ShowToast(TOAST_SUCCESS),
                    ),
                )
            }
            // T13b: 금방 다시 켜짐 → 현장에서 계속 지켜본다
            elapsed < cfg.retroMinElapsed -> startMonitoringFrom(snap, sample, input.now)
            // T12: 충분히 지났는데 그대로 → 과거형 질문
            delta < cfg.successDeltaLivePct -> Transition(
                AskingRetro(snap, soc, input.now),
                listOf(Effect.Ask(questionRetro(snap.stationName), retro = true)),
            )
            // T13c: 1%p만 오름 → 판단 보류, 기록하지 않음
            else -> Transition(Idle, listOf(Effect.ClearSnapshot))
        }
    }

    private fun onAskingRetro(state: AskingRetro, input: SessionInput): Transition = when (input) {
        is Answer ->
            if (input.intent == VoiceIntent.YES) {
                Transition( // T14
                    Failed(state.snap),
                    listOf(
                        Effect.Upload(event(state.snap, ChargeResult.FAIL, DetectMode.RETRO, state.currentSoc, input.at)),
                        Effect.ClearSnapshot,
                    ),
                )
            } else {
                Transition(Idle, listOf(Effect.ClearSnapshot)) // T15
            }
        // T25: 재시동 때 질문이 떴는데 답하지 않고 출발 → 확인 카드로 넘긴다
        is Departing -> departedWithoutCharge(state.snap, DetectMode.RETRO, state.currentSoc, input.at)
        is Moved -> leftWithoutResult(state, state.snap, DetectMode.RETRO, state.currentSoc, input)
        else -> stay(state)
    }

    private fun onEnded(input: SessionInput): Transition {
        // T19: 리뷰를 마치면 초기화. 다른 입력이 먼저 오면 초기화 후 Idle에서 처리한다.
        if (input == ReviewDone) return Transition(Idle, listOf(Effect.ClearSnapshot))
        val next = onIdle(input)
        return next.copy(effects = listOf(Effect.ClearSnapshot) + next.effects)
    }

    // ---------------------------------------------------------------- 도우미

    private fun startCandidate(station: Station, at: Instant) =
        Transition(Candidate(station, at), listOf(Effect.ScheduleTick(at + cfg.onsiteMinStop)))

    private fun snapshot(station: Station, arrivedAt: Instant, soc: SocSample) = ArrivalSnapshot(
        stationId = station.id,
        stationName = station.name,
        chargerType = station.chargerType,
        soc = soc.pct,
        at = arrivedAt,
        location = station.location,
    )

    private fun startMonitoring(station: Station, arrivedAt: Instant, soc: SocSample, now: Instant): Transition {
        val snap = snapshot(station, arrivedAt, soc)
        val deadline = now + cfg.stallTimeout(station.chargerType)
        return Transition(
            Monitoring(snap, soc, deadline),
            listOf(Effect.SaveSnapshot(snap), Effect.ScheduleTick(deadline)),
        )
    }

    private fun startMonitoringFrom(snap: ArrivalSnapshot, sample: SocSample, now: Instant): Transition {
        val deadline = now + cfg.stallTimeout(snap.chargerType)
        return Transition(Monitoring(snap, sample, deadline), listOf(Effect.ScheduleTick(deadline)))
    }

    private fun isLiveSuccess(snap: ArrivalSnapshot, sample: SocSample) =
        sample.pct >= snap.soc + cfg.successDeltaLivePct

    private fun liveSuccess(snap: ArrivalSnapshot, sample: SocSample): Transition {
        val sent = snap.copy(successSent = true)
        return Transition(
            Charging(sent, sample),
            listOf(
                Effect.Upload(event(snap, ChargeResult.SUCCESS, DetectMode.LIVE, sample.pct, sample.at)),
                Effect.SaveSnapshot(sent),
                Effect.ShowToast(TOAST_SUCCESS),
            ),
        )
    }

    /** 미리 만든 FAIL 기록을 운전자 확인에 맡긴다. YES일 때만 SessionCoordinator가 올린다. */
    private fun confirm(snap: ArrivalSnapshot, mode: DetectMode, socEnd: Int, at: Instant) =
        Effect.ConfirmLater(event(snap, ChargeResult.FAIL, mode, socEnd, at), snap.stationName)

    /**
     * 잔량이 오르지 않은 채 출발. Monitoring·Asking·AskingRetro는 이미 onsiteMinStop(5분)을 채운 상태라
     * 항상 '꽂아 봤는데 안 됨'일 수 있으므로 확인을 맡긴다.
     */
    private fun departedWithoutCharge(snap: ArrivalSnapshot, mode: DetectMode, socEnd: Int, at: Instant) =
        Transition(Idle, listOf(Effect.ClearSnapshot, confirm(snap, mode, socEnd, at)))

    /** T22: 주행 신호(Departing) 없이 위치로만 떠난 걸 알았을 때의 대비. 떠난 시각은 몰라서 마지막 잔량 시각을 쓴다. */
    private fun leftWithoutResult(state: SessionState, snap: ArrivalSnapshot, mode: DetectMode, socEnd: Int, input: Moved): Transition =
        if (input.distanceFromStationM > cfg.leftRadiusM) {
            departedWithoutCharge(snap, mode, socEnd, lastSeen(state) ?: snap.at)
        } else {
            stay(state)
        }

    private fun lastSeen(state: SessionState): Instant? = when (state) {
        is Monitoring -> state.lastSoc.at
        is Asking -> state.lastSoc.at
        is AskingRetro -> state.resumedAt
        else -> null
    }

    private fun leave(snap: ArrivalSnapshot, result: ChargeResult, input: Moved): Transition? =
        if (input.distanceFromStationM > cfg.leftRadiusM) {
            Transition(Ended(snap, result), listOf(Effect.OfferReview(snap.stationId)))
        } else {
            null
        }

    private fun event(
        snap: ArrivalSnapshot,
        result: ChargeResult,
        mode: DetectMode,
        socEnd: Int,
        at: Instant,
    ) = ChargeEvent(
        eventId = ids.newEventId(),
        deviceId = deviceId,
        stationId = snap.stationId,
        result = result,
        mode = mode,
        socStart = snap.soc,
        socEnd = socEnd,
        minutes = Duration.between(snap.at, at).toMinutes().coerceAtLeast(0).toInt(),
        occurredAt = at,
    )

    private fun stay(state: SessionState) = Transition(state)

    companion object {
        const val QUESTION_LIVE = "충전이 안 되나요?"
        const val SAY_FAIL_RECORDED = "기록했어요. 다른 충전소를 찾아볼까요?"
        const val TOAST_SUCCESS = "이 충전소에 ‘정상’ 1건을 남겼어요"
        fun questionRetro(stationName: String) = "아까 ${stationName}에서 충전이 안 됐나요?"
    }
}
