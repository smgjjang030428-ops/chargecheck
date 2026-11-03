package com.chargecheck.feature.warning

import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationStatus
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.port.ChargeCheckApiPort
import com.chargecheck.core.port.LocationPort
import com.chargecheck.core.port.NaviPort
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.core.port.VoiceIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 화면에 띄울 도착 전 경고 한 건. */
data class ArrivalWarning(
    val station: Station,
    val status: StationStatus,
    /** 가까운 GREEN 충전소. 없으면 [그대로]만 보인다. */
    val alternative: Station?,
    /** 대체 충전소의 판정("30분 전 충전됨" 같은 근거를 함께 보여 준다). */
    val alternativeStatus: StationStatus? = null,
    /** 경고할 때 그 충전소가 경유지였으면 순번(0부터), 목적지면 null. [바꾸기]는 이 값 대신 누를 때의 경로를 다시 본다. */
    val waypointIndex: Int? = null,
) {
    /** 서버 문장이 있으면 그것을(대수가 들어 있음), 없으면 이유 코드 문장. */
    val reasonText: String get() = status.message.ifBlank { reasonText(status.reasonCode) }
}

/** 내비 경로의 충전소와 그 판정. [status]가 null이면 아직 확인 중(조회 중이거나 실패 뒤 다시 조회를 기다림). */
data class RouteCheck(val station: Station, val status: StationStatus?)

/**
 * F3 도착 전 경고 = 내비가 고른 충전소 검증. 내비 경로(목적지·경유지)에 충전소가 들어오면 — 운전자가 넣었든 내비가
 * 충전소를 자동 추천해 넣었든 — 판정을 조회하고, YELLOW·RED면 경고와 대체 충전소(실제 충전 근거와 함께)를 띄운다.
 * 차지체크는 내비의 충전 추천과 경쟁하지 않고, 그 추천이 실제로 되는 곳인지 확인한다(기획 §7).
 *
 * - 같은 충전소는 판정 조회에 성공한 뒤 다시 경고하지 않는다. 조회에 실패하면 경로가 그대로여도 [retryDelaysMs] 간격으로 다시 조회한다.
 * - GREEN·GRAY면 말하지 않는다.
 * - "바꾸기"를 고르면 경고한 충전소 자리만 대체 충전소로 바꾼다(경유지면 그 경유지, 목적지면 목적지. 나머지 경로는 그대로).
 */
class ArrivalWarningCoordinator(
    private val navi: NaviPort,
    private val api: ChargeCheckApiPort,
    private val location: LocationPort,
    private val resolveStation: (RouteTarget) -> Station?,
    private val log: (String) -> Unit = {},
    /** 설정에서 '도착 전 경고'를 끄면 false. 꺼져 있으면 판정을 조회하지 않는다. */
    private val enabled: () -> Boolean = { true },
    /** 판정 조회 실패 뒤 다시 조회할 간격. 마지막 값을 계속 쓴다. */
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    /** [바꾸기] 뒤 내비 경로에 실제로 반영됐는지 기다리는 시간. */
    private val confirmTimeoutMs: Long = 10_000,
) {
    private val _warning = MutableStateFlow<ArrivalWarning?>(null)
    val warning: StateFlow<ArrivalWarning?> = _warning.asStateFlow()

    /** [바꾸기]의 진행 상태 문장("바꾸는 중", "바뀌었어요", "확인 못 함"). 화면 배너·내비 위 알림이 쓴다. */
    private val _swapStatus = MutableStateFlow<String?>(null)
    val swapStatus: StateFlow<String?> = _swapStatus.asStateFlow()

    /** 지금 내비 경로에서 검사하는 충전소와 판정. 첫 화면이 "내비가 고른 충전소"를 먼저 보여 줄 때 쓴다. */
    private val _routeCheck = MutableStateFlow<RouteCheck?>(null)
    val routeCheck: StateFlow<RouteCheck?> = _routeCheck.asStateFlow()

    /** 판정 조회에 성공한 충전소. 같은 충전소로 반복 경고하지 않는다. */
    private var checked: StationId? = null
    /** 조회 중이거나 재조회를 기다리는 충전소. 같은 충전소를 동시에 두 번 조회하지 않는다. */
    private var watching: StationId? = null
    /** 경로가 바뀔 때마다 늘린다. 이전 경로의 늦은 응답이 새 경로의 경고를 덮어쓰지 않게 한다. */
    private var generation = 0
    private var lastTargets: List<RouteTarget> = emptyList()
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var cancellationJob: Job? = null
    private var retryJob: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        this.scope = scope
        job = scope.launch {
            navi.routeTargets().collect { onTargets(it) }
        }
        cancellationJob = scope.launch {
            navi.routeCancellations().collect { clearRoute() }
        }
    }

    /**
     * 경로의 첫 충전소를 검사한다. 앱이 충전소 목록을 새로 받아 못 알아보던 경유지를 알아보게 되면 다시 부른다.
     * 경로를 다시 짜는 동안 잠깐 빈 목록이 와도 진행 중인 검사·재조회는 그대로 둔다.
     */
    suspend fun onTargets(targets: List<RouteTarget>) {
        if (targets.isNotEmpty()) lastTargets = targets
        val index = targets.indexOfFirst { resolveStation(it) != null }
        if (index < 0) {
            // 빈 목록은 재탐색 중일 수 있다. 명시적 취소는 routeCancellations에서 별도로 받는다.
            if (targets.isNotEmpty()) clearRoute(keepTargets = true)
            return
        }
        val station = resolveStation(targets[index]) ?: return
        if (!enabled()) {
            clearRoute(keepTargets = true)
            return
        }
        if (station.id == checked || station.id == watching) return
        val gen = ++generation
        retryJob?.cancel()
        watching = station.id
        _routeCheck.value = RouteCheck(station, null)
        val waypointIndex = index.takeIf { it < targets.lastIndex }
        if (check(station, waypointIndex, gen)) return
        retryJob = scope?.launch { retry(station, waypointIndex, gen) }
    }

    /** 설정에서 경고를 다시 켰을 때: 지금 경로를 처음부터 다시 검사한다. */
    suspend fun recheck() {
        retryJob?.cancel()
        checked = null
        watching = null
        generation++
        onTargets(lastTargets)
    }

    fun stop() {
        job?.cancel()
        job = null
        cancellationJob?.cancel()
        cancellationJob = null
        retryJob?.cancel()
        retryJob = null
        watching = null
        generation++
    }

    fun reset() {
        stop()
        clearRoute()
    }

    /** 구독은 유지하고 이전 경로의 작업·표시만 무효화한다. */
    private fun clearRoute(keepTargets: Boolean = false) {
        generation++
        retryJob?.cancel()
        retryJob = null
        checked = null
        watching = null
        if (!keepTargets) lastTargets = emptyList()
        _warning.value = null
        _swapStatus.value = null
        _routeCheck.value = null
    }

    private suspend fun retry(station: Station, waypointIndex: Int?, gen: Int) {
        var attempt = 0
        while (true) {
            delay(retryDelaysMs[minOf(attempt++, retryDelaysMs.lastIndex)])
            if (gen != generation) return
            if (!enabled()) {
                watching = null
                return
            }
            if (check(station, waypointIndex, gen)) return
        }
    }

    /** 운전자 선택. SWITCH = 대체 충전소로, 그 밖(KEEP·무응답) = 그대로. 앱 화면과 내비 위 알림이 같이 쓴다. */
    suspend fun choose(intent: VoiceIntent?) {
        val w = _warning.value ?: return
        _warning.value = null
        val alt = w.alternative
        if (intent != VoiceIntent.SWITCH || alt == null) {
            log("경고 닫음: ${w.station.name}(으)로 그대로 감")
            return
        }
        val target = RouteTarget(alt.name, alt.location, alt.id.value)
        val route = stableRoute()
        val index = route?.indexOfFirst { resolveStation(it)?.id == w.station.id } ?: -1
        if (route == null || index < 0) {
            // 경고한 뒤 경로가 바뀌었거나 아직 다시 짜는 중: 오래된 순번으로 다른 경유지를 바꾸지 않는다
            swap("경로를 확인하고 있어요 · ${w.station.name}이(가) 지금 경로에 없어 바꾸지 않았어요")
            return
        }
        val isWaypoint = index < route.lastIndex
        val what = if (isWaypoint) "경유지" else "목적지"
        val result = if (isWaypoint) {
            navi.replaceWaypoint(index, target)
        } else {
            val waypoints = route.dropLast(1).map { it.withLocation() }
            if (waypoints.any { it == null }) {
                swap("경유지 위치를 몰라 목적지를 바꾸지 않았어요")
                restoreWarningIfCurrent(w)
                return
            }
            navi.replaceDestination(target, waypoints.filterNotNull())
        }
        if (result.isFailure) {
            swap("$what 바꾸기 실패 · 다시 눌러 주세요")
            log("$what 교체 실패: ${w.station.name} → ${alt.name} (${result.exceptionOrNull()?.message})")
            restoreWarningIfCurrent(w)
            return
        }
        swap("${alt.name}(으)로 바꾸는 중")
        log("$what 교체 요청: ${w.station.name} → ${alt.name}")
        // 요청이 전달된 것과 경로에 반영된 것은 다르다. 내비 경로에 대안이 들어오고 원래 충전소가 빠질 때까지 본다.
        val applied = withTimeoutOrNull(confirmTimeoutMs) {
            navi.routeTargets().first { t -> t.any { it.isStation(alt) } && t.none { resolveStation(it)?.id == w.station.id } }
        }
        if (applied != null) {
            swap("${alt.name}(으)로 바뀌었어요")
            log("$what 교체 확인: ${applied.joinToString { it.name }}")
        } else {
            swap("내비 경로가 바뀌었는지 확인하지 못했어요 · 내비 화면을 확인해 주세요")
            log("$what 교체 확인 실패(${confirmTimeoutMs / 1000}초): ${w.station.name} → ${alt.name}")
            restoreWarningIfCurrent(w)
        }
    }

    /** 요청 실패·확인 시간 초과 뒤에도 원래 충전소가 남아 있을 때만 다시 선택하게 한다. */
    private suspend fun restoreWarningIfCurrent(warning: ArrivalWarning) {
        if (!enabled()) return
        val current = withTimeoutOrNull(1_000) { navi.routeTargets().first() } ?: return
        if (current.any { resolveStation(it)?.id == warning.station.id } &&
            (_warning.value == null || _warning.value?.station?.id == warning.station.id)
        ) {
            _warning.value = warning
        }
    }

    /** 진행 상태를 알리고, 바뀌지 않으면 15초 뒤 지운다. */
    private fun swap(text: String) {
        _swapStatus.value = text
        scope?.launch {
            delay(SWAP_STATUS_MS)
            if (_swapStatus.value == text) _swapStatus.value = null
        }
    }

    /**
     * 누를 때의 경로. 경유지를 넣은 직후 내비가 경로를 다시 짜는 동안에는 목적지가 잠깐 비어 경유지가 목적지처럼 보인다
     * (에뮬레이터 2026-10-28). 그래서 목적지가 다시 보일 때까지 잠깐 기다린다. 끝내 안 보이면 null(경로 불명확).
     */
    private suspend fun stableRoute(): List<RouteTarget>? = withTimeoutOrNull(3_000) {
        navi.destination().first { it != null }
        navi.routeTargets().first().takeIf { it.isNotEmpty() }
    }

    /** 좌표가 없는(0,0) 경유지는 알아본 충전소 위치로 채운다. 그래도 모르면 null. */
    private fun RouteTarget.withLocation(): RouteTarget? = when {
        location.lat != 0.0 || location.lon != 0.0 -> this
        else -> resolveStation(this)?.let { copy(location = it.location) }
    }

    private fun RouteTarget.isStation(s: Station) = resolveStation(this)?.id == s.id || poiId == s.id.value || name.trim() == s.name.trim()

    /** 판정을 조회한다. 조회에 성공하면 true(경고 여부와 상관없이). 실패하면 false → 다시 조회. */
    private suspend fun check(station: Station, waypointIndex: Int?, gen: Int): Boolean {
        val status = api.stationStatus(station.id).getOrNull()
        if (gen != generation) return true // 그사이 경로가 바뀜: 이 결과는 버린다
        if (status == null) {
            log("목적지 ${station.name}: 판정 조회 실패 → 잠시 뒤 다시 조회")
            return false
        }
        checked = station.id
        watching = null
        _routeCheck.value = RouteCheck(station, status)
        if (!shouldWarn(status)) {
            _warning.value = null
            log("목적지 ${station.name}: ${status.verdict} → 경고 없음" + if (status.message.isNotBlank()) " (${status.message})" else "")
            return true
        }
        // 대안은 경고한 충전소 주변에서 찾는다. 차 위치로 찾으면 먼 목적지 대신 출발지 옆 충전소로 바꾸게 된다
        val here = station.location
        // 대안 조회가 실패해도 위험 경고는 띄운다
        val alternatives = api.alternatives(station.id, here)
        if (gen != generation) return true
        val alternative = pickAlternative(alternatives.getOrNull().orEmpty())
        _warning.value = ArrivalWarning(station, status, alternative?.first, alternative?.second, waypointIndex)
        log("도착 전 경고: ${station.name} ${status.verdict} — ${status.message.ifBlank { reasonText(status.reasonCode) }}")
        // 대안 조회만 실패했으면 경고는 그대로 두고 대안을 한 번 더 찾아 [바꾸기]를 붙인다(같은 경고를 다시 띄우지 않음)
        if (alternatives.isFailure) scope?.launch {
            delay(retryDelaysMs.first())
            val again = pickAlternative(api.alternatives(station.id, here).getOrNull().orEmpty()) ?: return@launch
            val w = _warning.value
            if (gen == generation && w != null && w.station.id == station.id && w.alternative == null) {
                _warning.value = w.copy(alternative = again.first, alternativeStatus = again.second)
            }
        }
        return true
    }

    /** 초록(최근 정상) 우선, 없으면 경고 대상이 아닌 가장 가까운 곳. */
    private fun pickAlternative(candidates: List<Pair<Station, StationStatus>>) =
        candidates.firstOrNull { it.second.verdict == Verdict.GREEN } ?: candidates.firstOrNull { !shouldWarn(it.second) }

    companion object {
        /** 판정 조회 실패 뒤 5초 → 15초 → 30초 → 이후 60초마다. */
        val RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L, 60_000L)
        private const val SWAP_STATUS_MS = 15_000L
    }
}

/**
 * 경고는 RED, 또는 일부 충전기만의 문제가 아닌 YELLOW에만 띄운다(기획 §6).
 * 충전기 몇 대만 실패하고 나머지가 쓸 수 있으면 운전을 방해하지 않는다.
 */
fun shouldWarn(status: StationStatus): Boolean = when (status.verdict) {
    Verdict.RED -> true
    // 일부 충전기만 실패했거나, 운영사가 수리 상태로 바꾼 곳은 겁주지 않는다
    Verdict.YELLOW -> status.reasonCode != ReasonCode.SOME_CHARGERS_FAIL && status.reasonCode != ReasonCode.REPAIRED_UNCONFIRMED
    Verdict.GREEN, Verdict.GRAY -> false
}

fun reasonText(code: ReasonCode): String = when (code) {
    ReasonCode.RECENT_SUCCESS -> "최근 정상 충전 기록이 있어요"
    ReasonCode.FAIL_AFTER_SUCCESS -> "최근 정상 기록 뒤에 실패 기록이 있어요"
    ReasonCode.SOME_CHARGERS_FAIL -> "일부 충전기에 최근 실패 기록이 있어요"
    ReasonCode.MULTI_FAIL -> "최근 여러 차량이 충전에 실패했어요"
    ReasonCode.PUBLIC_OUT_OF_SERVICE -> "공공 정보상 고장·점검 중이에요"
    ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS -> "공공 정보는 '사용 가능'인데 실제로는 실패 기록이 있어요"
    ReasonCode.NO_RECENT_DATA -> "최근 기록이 없어요"
    ReasonCode.PUBLIC_CHARGING -> "최근 다른 차가 충전했어요(운영사 데이터)"
    ReasonCode.OLD_FAILS -> "몇 시간 전 실패 제보가 있고 그 뒤 확인 기록이 없어요"
    ReasonCode.REPAIRED_UNCONFIRMED -> "운영사 상태가 '사용 가능'으로 바뀌었어요(수리됐을 수 있음)"
}
