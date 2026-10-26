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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 화면에 띄울 도착 전 경고 한 건. */
data class ArrivalWarning(
    val station: Station,
    val status: StationStatus,
    /** 가까운 GREEN 충전소. 없으면 [그대로]만 보인다. */
    val alternative: Station?,
) {
    /** 서버 문장이 있으면 그것을(대수가 들어 있음), 없으면 이유 코드 문장. */
    val reasonText: String get() = status.message.ifBlank { reasonText(status.reasonCode) }
}

/**
 * F3 도착 전 경고. 내비 목적지가 충전소로 바뀌면 판정을 조회하고, YELLOW·RED면 경고와 대체 충전소를 띄운다.
 * (기획문서/차지체크_앱_구동_기획.md §6)
 *
 * - 목적지가 바뀔 때 1회만 경고한다. 같은 충전소로 반복 경고하지 않는다.
 * - GREEN·GRAY면 말하지 않는다.
 * - "바꾸기"를 고르면 대체 충전소를 경유지로 넣는다.
 */
class ArrivalWarningCoordinator(
    private val navi: NaviPort,
    private val api: ChargeCheckApiPort,
    private val location: LocationPort,
    private val resolveStation: (RouteTarget) -> Station?,
    private val log: (String) -> Unit = {},
    /** 설정에서 '도착 전 경고'를 끄면 false. 꺼져 있으면 판정을 조회하지 않는다. */
    private val enabled: () -> Boolean = { true },
) {
    private val _warning = MutableStateFlow<ArrivalWarning?>(null)
    val warning: StateFlow<ArrivalWarning?> = _warning.asStateFlow()

    private var lastChecked: StationId? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            navi.routeTargets().collect { targets ->
                val station = targets.firstNotNullOfOrNull(resolveStation) ?: return@collect
                if (station.id == lastChecked || !enabled()) return@collect
                lastChecked = station.id
                check(station)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun reset() {
        stop()
        lastChecked = null
        _warning.value = null
    }

    /** 운전자 선택. SWITCH = 대체 충전소로, 그 밖(KEEP·무응답) = 그대로. */
    suspend fun choose(intent: VoiceIntent?) {
        val w = _warning.value ?: return
        _warning.value = null
        val alt = w.alternative
        if (intent == VoiceIntent.SWITCH && alt != null) {
            navi.addWaypoint(RouteTarget(alt.name, alt.location, alt.id.value))
            log("경유지 추가: ${alt.name}")
        } else {
            log("경고 닫음: ${w.station.name}(으)로 그대로 감")
        }
    }

    private suspend fun check(station: Station) {
        val status = api.stationStatus(station.id).getOrNull() ?: return
        if (!shouldWarn(status)) {
            _warning.value = null
            log("목적지 ${station.name}: ${status.verdict} → 경고 없음" + if (status.message.isNotBlank()) " (${status.message})" else "")
            return
        }
        val here = location.lastKnown() ?: station.location
        // 초록(최근 정상) 우선, 없으면 경고 대상이 아닌 가장 가까운 곳
        val candidates = api.alternatives(station.id, here).getOrNull().orEmpty()
        val alternative = (candidates.firstOrNull { it.second.verdict == Verdict.GREEN } ?: candidates.firstOrNull { !shouldWarn(it.second) })?.first
        _warning.value = ArrivalWarning(station, status, alternative)
        log("도착 전 경고: ${station.name} ${status.verdict} — ${status.message.ifBlank { reasonText(status.reasonCode) }}")
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
