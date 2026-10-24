package com.chargecheck.feature.session

import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.ChargerStatus
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.ChargeCheckApiPort
import com.chargecheck.core.port.ClockPort
import com.chargecheck.core.port.LocationPort
import com.chargecheck.core.port.NaviPort
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.core.port.VehiclePort
import com.chargecheck.core.port.VoiceIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** 화면에 보여줄 기록 한 줄. */
data class LogEntry(val at: Instant, val text: String)

/** 실패 기록 뒤 "몇 번 충전기였나요?" 질문. 주차 중에만 화면에 띄운다. */
data class ChargerPick(val eventId: String, val stationName: String, val chargers: List<ChargerStatus>)

/**
 * 포트의 Flow를 [SessionInput]으로 바꿔 [ChargeSessionMachine.reduce]에 넣고, 나온 [Effect]를 실행한다.
 * 상태는 [state]로 UI에 노출한다. (기획문서/앱구조_명세서_AI용.md §4.7 끝)
 *
 * @param resolveStation 내비 목적지가 충전소인지 판별한다. §4.8 StationResolver가 생기기 전까지의 자리.
 */
class SessionCoordinator(
    private val machine: ChargeSessionMachine,
    private val vehicle: VehiclePort,
    private val navi: NaviPort,
    private val location: LocationPort,
    private val api: ChargeCheckApiPort,
    private val clock: ClockPort,
    private val resolveStation: (RouteTarget) -> Station?,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** 지금 운전자에게 묻고 있는 질문. 답하거나 상태가 바뀌면 null. */
    private val _question = MutableStateFlow<Effect.Ask?>(null)
    val question: StateFlow<Effect.Ask?> = _question.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 실패 기록 뒤 충전기 번호 질문. 답하거나 건너뛰면 null. */
    private val _chargerPick = MutableStateFlow<ChargerPick?>(null)
    val chargerPick: StateFlow<ChargerPick?> = _chargerPick.asStateFlow()

    /** false = 시동이 꺼져 앱이 멈춘 상태. 이 동안 들어오는 포트 이벤트는 버린다. */
    private val _running = MutableStateFlow(true)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val mutex = Mutex()
    private val pendingTicks = sortedSetOf<Instant>()
    private var targetPct: Int? = null
    private var jobs: List<Job> = emptyList()

    fun start(scope: CoroutineScope, time: Flow<Instant>) {
        stop()
        jobs = listOf(
            scope.launch {
                navi.routeTargets().collect { targets ->
                    if (!_running.value) return@collect
                    targets.firstNotNullOfOrNull(resolveStation)?.let { dispatch(SessionInput.RouteTargetIsStation(it)) }
                }
            },
            scope.launch {
                navi.arrivals().collect { target ->
                    if (!_running.value) return@collect
                    resolveStation(target)?.let { dispatch(SessionInput.Arrived(it, clock.now())) }
                }
            },
            scope.launch {
                vehicle.soc().collect { sample ->
                    if (!_running.value) return@collect
                    dispatch(SessionInput.Soc(sample))
                    val target = targetPct ?: vehicle.chargeTargetPct().also { targetPct = it }
                    if (target != null && sample.pct >= target && _state.value is SessionState.Charging) {
                        dispatch(SessionInput.TargetReached(sample.pct))
                    }
                }
            },
            scope.launch {
                location.locations().collect { here ->
                    if (!_running.value) return@collect
                    val there = _state.value.stationLocation() ?: return@collect
                    dispatch(SessionInput.Moved(here.distanceMetersTo(there).toInt()))
                }
            },
            scope.launch {
                time.collect { now ->
                    if (!_running.value) return@collect
                    val due = mutex.withLock {
                        pendingTicks.filter { it <= now }.also { pendingTicks.removeAll(it.toSet()) }
                    }
                    if (due.isNotEmpty()) dispatch(SessionInput.Tick(now))
                }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** 시연을 처음부터 다시 할 때. */
    fun reset() {
        stop()
        pendingTicks.clear()
        targetPct = null
        _running.value = true
        _state.value = SessionState.Idle
        _log.value = emptyList()
        _question.value = null
        _toast.value = null
        _chargerPick.value = null
    }

    /** 충전기 번호 선택. null이면 "모르겠어요"(건너뜀). */
    suspend fun pickCharger(chargerId: String?) {
        val pick = _chargerPick.value ?: return
        _chargerPick.value = null
        if (chargerId == null) {
            log("충전기 번호: 건너뜀")
            return
        }
        val ok = api.attachCharger(pick.eventId, chargerId).isSuccess
        log("충전기 번호: ${chargerId}번" + if (ok) "" else " — 서버 반영 실패")
    }

    /** 음성 또는 버튼 응답. */
    suspend fun answer(intent: VoiceIntent?) = dispatch(SessionInput.Answer(intent, clock.now()))

    /** 시동이 꺼져 앱 프로세스가 멈춤. 예약된 Tick은 버린다(꺼진 동안은 판단하지 않는다). */
    suspend fun appStopped() {
        if (!_running.value) return
        dispatch(SessionInput.AppStopped)
        mutex.withLock { pendingTicks.clear() }
        _running.value = false
        log("시동 꺼짐 → 앱 멈춤")
    }

    /** 앱이 다시 켜짐. 꺼지기 전 스냅샷과 지금 SOC를 비교한다(RETRO). */
    suspend fun appResumed() {
        if (_running.value) return
        _running.value = true
        log("시동 켜짐 → 앱 다시 실행 (SOC ${vehicle.currentSoc() ?: "?"}%)")
        dispatch(SessionInput.AppResumed(vehicle.currentSoc(), location.lastKnown(), clock.now()))
    }

    /** 다른 기능(도착 전 경고 등)이 같은 기록 화면에 남길 때. */
    fun note(text: String) = log(text)

    suspend fun dispatch(input: SessionInput) {
        val effects = mutex.withLock {
            val before = _state.value
            val t = machine.reduce(before, input)
            _state.value = t.state
            if (t.state::class != before::class) log("상태: ${before.label()} → ${t.state.label()}")
            if (t.state !is SessionState.Asking && t.state !is SessionState.AskingRetro) _question.value = null
            t.effects
        }
        effects.forEach { run(it) }
    }

    private suspend fun run(effect: Effect) {
        when (effect) {
            is Effect.Upload -> {
                val e = effect.event
                val ok = api.postEvent(e).isSuccess
                log("서버 기록: ${e.result} (${e.mode}, SOC ${e.socStart}→${e.socEnd}%)" + if (ok) "" else " — 실패, 재시도 대기")
                if (ok && e.result == ChargeResult.FAIL) offerChargerPick(e.eventId, e.stationId)
            }
            is Effect.Ask -> {
                _question.value = effect
                log("질문: ${effect.question}")
            }
            is Effect.Say -> log("안내: ${effect.text}")
            is Effect.ShowToast -> {
                _toast.value = effect.text
                log("알림: ${effect.text}")
            }
            is Effect.ScheduleTick -> mutex.withLock { pendingTicks += effect.at }
            // 정상 기록 뒤 successSent 표시만 바꾸는 저장은 기록하지 않는다
            is Effect.SaveSnapshot -> if (!effect.snap.successSent) log("도착 스냅샷 저장 (SOC ${effect.snap.soc}%)")
            Effect.ClearSnapshot -> Unit
            Effect.NotifyComplete -> log("알림: 목표 충전량에 도달했어요")
            is Effect.OfferReview -> log("충전소를 떠남 → 평가 요청")
        }
    }

    /** 충전기가 2대 이상인 충전소에서만 묻는다(1대면 물을 필요가 없다). 조회 실패는 조용히 넘어간다. */
    private suspend fun offerChargerPick(eventId: String, stationId: StationId) {
        val status = runCatching { api.stationStatus(stationId).getOrNull() }.getOrNull() ?: return
        if (status.chargers.size < 2) return
        val name = (_state.value as? SessionState.Failed)?.snap?.stationName ?: stationId.value
        _chargerPick.value = ChargerPick(eventId, name, status.chargers)
        log("질문: 몇 번 충전기였나요?")
    }

    private fun log(text: String) = _log.update { it + LogEntry(clock.now(), text) }
}

/** 세션이 붙어 있는 충전소 좌표. 떠났는지(Moved) 계산할 때 쓴다. */
private fun SessionState.stationLocation(): GeoPoint? = when (this) {
    SessionState.Idle -> null
    is SessionState.Approaching -> station.location
    is SessionState.Candidate -> station.location
    is SessionState.Monitoring -> snap.location
    is SessionState.Asking -> snap.location
    is SessionState.Charging -> snap.location
    is SessionState.Completed -> snap.location
    is SessionState.Failed -> snap.location
    is SessionState.Suspended -> snap.location
    is SessionState.AskingRetro -> snap.location
    is SessionState.Ended -> null
}

/** 화면·기록에 쓰는 상태 이름. */
fun SessionState.label(): String = when (this) {
    SessionState.Idle -> "대기"
    is SessionState.Approaching -> "충전소로 가는 중"
    is SessionState.Candidate -> "충전소 도착 확인 중"
    is SessionState.Monitoring -> "충전 시작 지켜보는 중"
    is SessionState.Asking -> "운전자 확인 중"
    is SessionState.Charging -> "충전 중 (정상 기록됨)"
    is SessionState.Completed -> "충전 완료"
    is SessionState.Failed -> "고장 기록됨"
    is SessionState.Suspended -> "차량 꺼짐"
    is SessionState.AskingRetro -> "운전자 확인 중 (사후)"
    is SessionState.Ended -> "충전소를 떠남"
}
