package com.chargecheck.adapter.fake

import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.core.port.VoiceIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** 시나리오 한 단계. t는 시뮬레이션 초. station이 null이면 시나리오의 기본 충전소. */
sealed interface Step {
    val t: Int
    data class RouteTargetStep(override val t: Int, val station: Station? = null) : Step
    data class Arrive(override val t: Int, val station: Station? = null) : Step
    data class Soc(override val t: Int, val pct: Int) : Step
    /** 마지막으로 도착한 충전소에서 북쪽으로 meters만큼 떨어진 곳으로 이동. */
    data class Move(override val t: Int, val meters: Int) : Step
    /** 시동 끔(false)·켬(true). 앱 프로세스가 멈췄다 다시 뜨는 상황. */
    data class Power(override val t: Int, val on: Boolean) : Step
    /** 운전자 응답(FakeVoice 자리). 화면 버튼을 먼저 누르면 이 단계는 무시된다. */
    data class Answer(override val t: Int, val intent: VoiceIntent) : Step
}

/** 재생기가 Fake 포트 밖(앱 생명주기·음성 응답)으로 보내는 신호. 연결은 app 모듈이 한다. */
sealed interface Control {
    data class Power(val on: Boolean) : Control
    data class Answer(val intent: VoiceIntent) : Control
}

data class Scenario(
    val id: String,
    val title: String,
    val station: Station,
    val speed: Int,
    val targetPct: Int?,
    val steps: List<Step>,
    /** 서버(FakeApi)가 돌려줄 충전소 판정. 없으면 GRAY. */
    val verdicts: Map<StationId, Pair<Verdict, ReasonCode>> = emptyMap(),
)

/**
 * 시나리오를 배속으로 재생해 FakeClock과 각 Fake 포트에 값을 흘려보낸다. (§4.15)
 * 마지막 단계 뒤에도 시계는 계속 흐른다(정체 판정·질문 시간이 지나가도록).
 */
class ScenarioPlayer(
    private val clock: FakeClock,
    private val vehicle: FakeVehicle,
    private val navi: FakeNavi,
    private val location: FakeLocation,
    private val api: FakeApi,
) {
    private val _playing = MutableStateFlow<Scenario?>(null)
    val playing: StateFlow<Scenario?> = _playing.asStateFlow()
    private var job: Job? = null

    /** 시나리오에 나오는 모든 충전소(서버 충전소 목록 자리). */
    fun stationsOf(s: Scenario): List<Station> =
        (listOf(s.station) + s.steps.mapNotNull {
            when (it) {
                is Step.RouteTargetStep -> it.station
                is Step.Arrive -> it.station
                else -> null
            }
        }).distinctBy { it.id }

    fun play(
        scope: CoroutineScope,
        scenario: Scenario,
        start: Instant,
        speed: Int = scenario.speed,
        onControl: suspend (Control) -> Unit,
    ) {
        stop()
        _playing.value = scenario
        vehicle.clear()
        vehicle.targetPct = scenario.targetPct
        navi.setTargets(emptyList())
        api.configure(stationsOf(scenario), scenario.verdicts)
        clock.set(start)
        job = scope.launch {
            val pending = scenario.steps.sortedBy { it.t }.toMutableList()
            var here = scenario.station
            var simMs = 0L
            while (true) {
                while (pending.isNotEmpty() && pending.first().t * 1000L <= simMs) {
                    val at = start.plusMillis(pending.first().t * 1000L)
                    when (val s = pending.removeAt(0)) {
                        is Step.RouteTargetStep -> navi.setTargets(listOf((s.station ?: scenario.station).target()))
                        is Step.Arrive -> {
                            here = s.station ?: scenario.station
                            location.moveTo(here.location)
                            navi.arrive(here.target())
                        }
                        is Step.Soc -> vehicle.emitSoc(SocSample(s.pct, at))
                        is Step.Move -> location.moveTo(here.location.northBy(s.meters))
                        is Step.Power -> onControl(Control.Power(s.on))
                        is Step.Answer -> onControl(Control.Answer(s.intent))
                    }
                }
                delay(FRAME_MS)
                simMs += FRAME_MS * speed
                clock.set(start.plusMillis(simMs))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _playing.value = null
    }

    private companion object {
        const val FRAME_MS = 100L
    }
}

private fun Station.target() = RouteTarget(name, location, id.value)

private fun GeoPoint.northBy(meters: Int) = GeoPoint(lat + meters / 111_000.0, lon)

/** 시연용 필수 시나리오 6개. (§4.15) 시간 단위는 분으로 적고 초로 바꾼다. */
object DemoScenarios {
    val gangnamFast = Station(StationId("ME000001"), "강남 급속충전소", GeoPoint(37.4979, 127.0276), ChargerType.FAST)
    val yeoksamFast = Station(StationId("ME000003"), "역삼 급속충전소", GeoPoint(37.5006, 127.0364), ChargerType.FAST)
    val seochoFast = Station(StationId("ME000004"), "서초 급속충전소", GeoPoint(37.4919, 127.0076), ChargerType.FAST)

    private fun m(min: Int) = min * 60

    /** from%에서 to%까지 everyMin분마다 step%씩 오르는 SOC 단계들. 마지막 값은 정확히 to. */
    private fun rising(fromMin: Int, from: Int, to: Int, step: Int, everyMin: Int): List<Step> = buildList {
        var pct = from
        var t = fromMin
        while (pct < to) {
            add(Step.Soc(m(t), pct))
            pct += step
            t += everyMin
        }
        add(Step.Soc(m(t), to))
    }

    val liveSuccess = Scenario(
        id = "live_success",
        title = "LIVE 정상: 도착 → 충전 시작 → 정상 기록 → 완료 → 출발",
        station = gangnamFast,
        speed = 120,
        targetPct = 80,
        steps = buildList {
            add(Step.RouteTargetStep(0))
            add(Step.Arrive(m(2)))
            add(Step.Soc(m(2), 42))
            // 5분 정차 확인 뒤 3분마다 2%씩 올라 80%에서 완료
            addAll(rising(fromMin = 8, from = 43, to = 80, step = 2, everyMin = 3))
            add(Step.Move(m(68), 300))
        },
    )

    val liveFailThenSwitch = Scenario(
        id = "live_fail_then_switch",
        title = "LIVE 고장: SOC 그대로 → \"충전이 안 되나요?\" → 고장 기록 → 다른 충전소로",
        station = gangnamFast,
        speed = 60,
        targetPct = 80,
        steps = listOf(
            Step.RouteTargetStep(0),
            Step.Arrive(m(1)),
            Step.Soc(m(1), 42),
            Step.Soc(m(8), 42),
            Step.Soc(m(14), 42),
            Step.Answer(m(20), VoiceIntent.YES), // 16분에 질문, 4분 안에 버튼을 안 누르면 자동으로 "응"
            Step.Move(m(23), 300),
            Step.RouteTargetStep(m(24), yeoksamFast),
        ),
        verdicts = mapOf(yeoksamFast.id to (Verdict.GREEN to ReasonCode.RECENT_SUCCESS)),
    )

    val liveWaitThenSuccess = Scenario(
        id = "live_wait_then_success",
        title = "LIVE 대기: 질문 → \"아직 연결 중\" → 10분 연장 → 충전 시작 → 정상 기록",
        station = gangnamFast,
        speed = 60,
        targetPct = 60,
        steps = buildList {
            add(Step.RouteTargetStep(0))
            add(Step.Arrive(m(1)))
            add(Step.Soc(m(1), 42))
            add(Step.Answer(m(18), VoiceIntent.WAIT)) // 16분에 질문
            addAll(rising(fromMin = 21, from = 43, to = 60, step = 3, everyMin = 2))
            add(Step.Move(m(36), 300))
        },
    )

    val retroSuccess = Scenario(
        id = "retro_success",
        title = "RETRO 정상: 충전 꽂고 시동 끔 → 40분 뒤 켜니 SOC 상승 → 정상 기록",
        station = gangnamFast,
        speed = 120,
        targetPct = 80,
        steps = listOf(
            Step.RouteTargetStep(0),
            Step.Arrive(m(1)),
            Step.Soc(m(1), 42),
            Step.Power(m(8), on = false),
            Step.Soc(m(20), 51), // 꺼진 동안 충전됨(앱은 모름)
            Step.Soc(m(35), 63),
            Step.Soc(m(46), 71),
            Step.Power(m(48), on = true),
            Step.Soc(m(50), 72),
            Step.Move(m(54), 300),
        ),
    )

    val retroFail = Scenario(
        id = "retro_fail",
        title = "RETRO 고장: 꽂고 자리 비움 → 돌아와 시동 켜니 SOC 그대로 → 사후 질문",
        station = gangnamFast,
        speed = 120,
        targetPct = 80,
        steps = listOf(
            Step.RouteTargetStep(0),
            Step.Arrive(m(1)),
            Step.Soc(m(1), 42),
            Step.Power(m(8), on = false),
            Step.Power(m(45), on = true),
            Step.Answer(m(52), VoiceIntent.YES), // 45분에 질문, 7분 안에 안 누르면 자동으로 "응"
            Step.Move(m(55), 300),
        ),
    )

    val warningRedSwitch = Scenario(
        id = "warning_red_switch",
        title = "도착 전 경고: 목적지 빨강 → 근처 초록 충전소로 변경 → 정상 충전",
        station = gangnamFast,
        speed = 90,
        targetPct = 70,
        steps = buildList {
            add(Step.RouteTargetStep(0, seochoFast))
            add(Step.Answer(m(4), VoiceIntent.SWITCH)) // 4분 안에 안 누르면 자동으로 "바꾸기"
            add(Step.Arrive(m(10), gangnamFast))
            add(Step.Soc(m(10), 35))
            addAll(rising(fromMin = 17, from = 37, to = 70, step = 3, everyMin = 2))
            add(Step.Move(m(42), 300))
        },
        verdicts = mapOf(
            seochoFast.id to (Verdict.RED to ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS),
            gangnamFast.id to (Verdict.GREEN to ReasonCode.RECENT_SUCCESS),
            yeoksamFast.id to (Verdict.YELLOW to ReasonCode.FAIL_AFTER_SUCCESS),
        ),
    )

    val all = listOf(liveSuccess, liveFailThenSwitch, liveWaitThenSuccess, retroSuccess, retroFail, warningRedSwitch)

    val allStations = listOf(gangnamFast, yeoksamFast, seochoFast)
}
