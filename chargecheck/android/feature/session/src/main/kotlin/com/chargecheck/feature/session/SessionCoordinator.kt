package com.chargecheck.feature.session

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.ChargerStatus
import com.chargecheck.core.model.FailReason
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.ChargeCheckApiPort
import com.chargecheck.core.port.ClockPort
import com.chargecheck.core.port.DuplicateEventException
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** 화면에 보여줄 기록 한 줄. */
data class LogEntry(val at: Instant, val text: String)

/** 실패 기록 뒤 "몇 번 충전기였나요?" 질문. 주차 중에만 화면에 띄운다. */
data class ChargerPick(val eventId: String, val stationName: String, val chargers: List<ChargerStatus>)

/**
 * 잔량 변화 없이 충전소를 떠난 뒤 "충전이 안 됐나요?" 확인(Effect.ConfirmLater).
 * 출발 직후 주행 화면에 잠깐([visible]), 못 받으면 숨겼다가 다음 주차 때 한 번 더([askedAgain]) 묻는다.
 */
data class FailConfirm(
    val event: ChargeEvent,
    val stationName: String,
    val createdAt: Instant,
    val visible: Boolean = true,
    val askedAgain: Boolean = false,
) {
    val question: String get() = "아까 ${stationName}에서 충전이 안 됐나요?"
}

/**
 * 포트의 Flow를 [SessionInput]으로 바꿔 [ChargeSessionMachine.reduce]에 넣고, 나온 [Effect]를 실행한다.
 * 상태는 [state]로 UI에 노출한다. (기획문서/앱구조_명세서_AI용.md §4.7 끝)
 *
 * @param resolveStation 내비 목적지가 충전소인지 판별한다. §4.8 StationResolver가 생기기 전까지의 자리.
 * @param parked 주차(true)·주행(false). 주차→주행이면 [SessionInput.Departing]을 넣고, 못 받은 실패 확인을 다음 주차 때 다시 띄운다.
 */
class SessionCoordinator(
    private val machine: ChargeSessionMachine,
    private val vehicle: VehiclePort,
    private val navi: NaviPort,
    private val location: LocationPort,
    private val api: ChargeCheckApiPort,
    private val clock: ClockPort,
    private val resolveStation: (RouteTarget) -> Station?,
    private val parked: Flow<Boolean>? = null,
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

    /** 잔량 변화 없이 떠난 충전소의 실패 확인. 답하거나 시간이 지나면 null. */
    private val _confirm = MutableStateFlow<FailConfirm?>(null)
    val confirm: StateFlow<FailConfirm?> = _confirm.asStateFlow()

    private val cfg get() = machine.cfg

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
                var wasParked: Boolean? = null
                parked?.distinctUntilChanged()?.collect { now ->
                    val before = wasParked
                    wasParked = now
                    if (before == null || !_running.value) return@collect
                    if (before && !now) onDeparting() else if (!before && now) onParked()
                }
            },
            scope.launch {
                time.collect { now ->
                    if (!_running.value) return@collect
                    expireConfirm(now)
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
        _confirm.value = null
    }

    /** 실패 확인에 대한 답. yes = 충전이 안 됐음 → 미리 만든 FAIL 기록을 올린다. 아니면 아무것도 기록하지 않는다. */
    suspend fun answerConfirm(yes: Boolean) {
        val c = _confirm.value ?: return
        _confirm.value = null
        if (!yes) {
            log("확인: 충전 문제 아님 → 기록하지 않음")
            return
        }
        upload(c.event, c.stationName)
    }

    /**
     * 상세 카드의 [여기 충전 안 됐어요]. 현장(최근 충전소 근처)에 있었는지는 화면 쪽에서 확인하고 부른다.
     * 같은 충전소의 확인 질문이 떠 있으면 이 답으로 대신한다.
     */
    suspend fun reportFail(station: Station, reason: FailReason? = null, chargerId: String? = null): Boolean {
        if (_confirm.value?.event?.stationId == station.id) _confirm.value = null
        val detail = listOfNotNull(reason?.name, chargerId?.let { "${it}번" })
        log("직접 표시: ${station.name} 충전 안 됨" + if (detail.isEmpty()) "" else " (${detail.joinToString(", ")})")
        return upload(machine.manualFail(station, vehicle.currentSoc(), clock.now(), reason, chargerId), station.name)
    }

    /** 마지막으로 서버에 올라간 실패 기록. 운전자가 이유·번호를 다시 말하면 이걸 고친다. */
    var lastFail: ChargeEvent? = null
        private set

    /** 같은 충전소의 최근 실패 기록에 충전기 번호·이유를 붙이거나 고친다. */
    suspend fun amendLastFail(chargerId: String?, reason: FailReason?): Boolean {
        val e = lastFail ?: return false
        val ok = api.updateEvent(e.eventId, chargerId, reason).isSuccess
        if (ok) lastFail = e.copy(chargerId = chargerId ?: e.chargerId, reason = reason ?: e.reason)
        log("기록 고침: " + listOfNotNull(reason?.name, chargerId?.let { "${it}번" }).joinToString(", ") + if (ok) "" else " — 서버 반영 실패")
        if (ok && chargerId != null && _chargerPick.value?.eventId == e.eventId) _chargerPick.value = null
        return ok
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
            is Effect.Upload -> upload(effect.event, effect.event.stationId.value.let { id -> _state.value.stationName() ?: id })
            is Effect.ConfirmLater -> {
                _confirm.value = FailConfirm(effect.event, effect.stationName, clock.now())
                log("확인 요청: ${effect.stationName}에서 잔량 변화 없이 출발")
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

    /** 서버에 기록을 올리고, 실패 기록이면 충전기 번호를 묻는다. 같은 기기·충전소 2시간 중복은 서버가 받지 않는다. */
    private suspend fun upload(e: ChargeEvent, stationName: String): Boolean {
        val r = api.postEvent(e)
        val note = when {
            r.isSuccess -> ""
            r.exceptionOrNull() is DuplicateEventException -> " — 2시간 안에 이미 기록됨"
            else -> " — 실패, 재시도 대기"
        }
        log("서버 기록: ${e.result} (${e.mode}, SOC ${e.socStart}→${e.socEnd}%)$note")
        if (r.isSuccess && e.result == ChargeResult.FAIL) {
            lastFail = e
            // 번호를 이미 말했으면 다시 묻지 않는다
            if (e.chargerId == null) offerChargerPick(e.eventId, e.stationId, stationName)
        }
        return r.isSuccess
    }

    /** 주차 → 주행. 다음 주차 때 이미 한 번 더 물은 확인은 여기서 끝낸다(무응답은 기록하지 않음). */
    private suspend fun onDeparting() {
        if (_confirm.value?.askedAgain == true) {
            _confirm.value = null
            log("확인: 답이 없어 기록하지 않음")
        }
        dispatch(SessionInput.Departing(clock.now()))
    }

    /** 주행 → 주차. 출발할 때 못 받은 확인을 한 번 더 띄운다. */
    private fun onParked() {
        val c = _confirm.value ?: return
        if (!c.visible && !c.askedAgain) {
            _confirm.value = c.copy(visible = true, askedAgain = true)
            log("다시 묻기: ${c.question}")
        }
    }

    /** 주행 중 카드는 잠깐만 보이고, 너무 오래된 확인은 버린다. */
    private fun expireConfirm(now: Instant) {
        val c = _confirm.value ?: return
        val age = Duration.between(c.createdAt, now)
        when {
            age > cfg.confirmWindow -> {
                _confirm.value = null
                log("확인: 시간이 지나 기록하지 않음")
            }
            c.visible && !c.askedAgain && age > cfg.confirmDrivingShow -> {
                _confirm.value = c.copy(visible = false)
                log("주행 중 확인 카드 숨김 → 다음 주차 때 다시 물음")
            }
        }
    }

    /** 충전기가 2대 이상인 충전소에서만 묻는다(1대면 물을 필요가 없다). 조회 실패는 조용히 넘어간다. */
    private suspend fun offerChargerPick(eventId: String, stationId: StationId, name: String) {
        val status = runCatching { api.stationStatus(stationId).getOrNull() }.getOrNull() ?: return
        if (status.chargers.size < 2) return
        _chargerPick.value = ChargerPick(eventId, name, status.chargers)
        log("질문: 몇 번 충전기였나요?")
    }

    private fun log(text: String) = _log.update { it + LogEntry(clock.now(), text) }
}

private fun SessionState.stationName(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Approaching -> station.name
    is SessionState.Candidate -> station.name
    is SessionState.Monitoring -> snap.stationName
    is SessionState.Asking -> snap.stationName
    is SessionState.Charging -> snap.stationName
    is SessionState.Completed -> snap.stationName
    is SessionState.Failed -> snap.stationName
    is SessionState.Suspended -> snap.stationName
    is SessionState.AskingRetro -> snap.stationName
    is SessionState.Ended -> snap.stationName
}

/** 세션이 붙어 있는 충전소. 상세 카드의 [여기 충전 안 됐어요]를 현장에서만 보이게 할 때 쓴다. */
fun SessionState.stationId(): StationId? = when (this) {
    SessionState.Idle -> null
    is SessionState.Approaching -> null // 아직 도착 전
    is SessionState.Candidate -> station.id
    is SessionState.Monitoring -> snap.stationId
    is SessionState.Asking -> snap.stationId
    is SessionState.Charging -> snap.stationId
    is SessionState.Completed -> snap.stationId
    is SessionState.Failed -> snap.stationId
    is SessionState.Suspended -> snap.stationId
    is SessionState.AskingRetro -> snap.stationId
    is SessionState.Ended -> snap.stationId
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
