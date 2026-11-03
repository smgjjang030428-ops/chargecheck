package com.chargecheck.feature.session

import com.chargecheck.core.model.ArrivalSnapshot
import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.ChargerStatus
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.PublicState
import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.model.DetectMode
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.ReviewTag
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationStatus
import com.chargecheck.core.port.ChargeCheckApiPort
import com.chargecheck.core.port.ClockPort
import com.chargecheck.core.port.LocationPort
import com.chargecheck.core.port.NaviPort
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.core.port.VehiclePort
import com.chargecheck.core.port.VoiceIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 포트 → SessionInput → 상태 머신 → Effect 실행까지 이어지는지 확인한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {

    private val t0: Instant = Instant.parse("2026-11-10T08:00:00Z")
    private val station = Station(StationId("ME000001"), "강남 급속충전소", GeoPoint(37.4979, 127.0276), ChargerType.FAST)
    private val target = RouteTarget(station.name, station.location, station.id.value)

    private val now = MutableStateFlow(t0)
    private val soc = MutableSharedFlow<SocSample>(extraBufferCapacity = 16)
    private val targets = MutableStateFlow<List<RouteTarget>>(emptyList())
    private val arrivals = MutableSharedFlow<RouteTarget>(extraBufferCapacity = 4)
    private val locations = MutableSharedFlow<GeoPoint>(extraBufferCapacity = 4)
    private val parked = MutableStateFlow(true)
    private val port = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    private var here: GeoPoint? = null
    /** 같은 건물의 다른 운영사 충전소(강남 급속충전소에서 약 11m). */
    private val nextDoor = Station(StationId("ME000002"), "강남 완속충전소", GeoPoint(37.4980, 127.0276), ChargerType.SLOW)
    private var known = listOf(station)
    private val uploaded = mutableListOf<ChargeEvent>()
    private var socNow: Int? = null
    private val attached = mutableListOf<Pair<String, String>>()
    private val threeChargers = (1..3).map {
        ChargerStatus("0$it", ChargerType.FAST, PublicState.AVAILABLE, Verdict.GRAY, 0)
    }

    /** 앱 프로세스가 죽어도 남는 저장소 대신. */
    private val store = object : SnapshotStore {
        var snap: ArrivalSnapshot? = null
        override fun load() = snap
        override fun save(snap: ArrivalSnapshot) { this.snap = snap }
        override fun clear() { snap = null }
    }

    /** 새 앱 프로세스 하나. 저장소만 이어진다. */
    private fun newCoordinator(withParkedSignal: Boolean = true) = SessionCoordinator(
        machine = ChargeSessionMachine(DeviceId("test"), { "e${uploaded.size}" }),
        vehicle = object : VehiclePort {
            override fun soc() = soc
            override suspend fun currentSoc(): Int? = socNow
            override suspend fun chargeTargetPct(): Int? = 80
            override fun chargePortOpen() = port
        },
        navi = object : NaviPort {
            override fun routeTargets() = targets
            override fun arrivals() = arrivals
            override suspend fun addWaypoint(target: RouteTarget) = Result.success(Unit)
            override suspend fun setDestination(target: RouteTarget) = Result.success(Unit)
        },
        location = object : LocationPort {
            override fun locations() = locations
            override suspend fun lastKnown(): GeoPoint? = here
        },
        api = object : ChargeCheckApiPort {
            override suspend fun postEvent(event: ChargeEvent): Result<Unit> = Result.success(Unit).also { uploaded += event }
            override suspend fun attachCharger(eventId: String, chargerId: String): Result<Unit> =
                Result.success(Unit).also { attached += eventId to chargerId }
            override suspend fun stationStatus(id: StationId): Result<StationStatus> = Result.success(
                StationStatus(id, Verdict.GRAY, ReasonCode.NO_RECENT_DATA, null, 0, 3, 3, emptyList(), chargers = threeChargers),
            )
            override suspend fun nearbyStations(at: GeoPoint, radiusM: Int) = Result.success(listOf(station))
            override suspend fun alternatives(id: StationId, at: GeoPoint) = Result.success(emptyList<Pair<Station, StationStatus>>())
            override suspend fun postReview(stationId: StationId, tags: List<ReviewTag>) = Result.success(Unit)
        },
        clock = object : ClockPort { override fun now(): Instant = now.value },
        resolveStation = { if (it.poiId == station.id.value) station else null },
        parked = if (withParkedSignal) parked else null,
        stationsNear = { known },
        snapshots = store,
    )

    private val coordinator = newCoordinator()

    private fun at(min: Long) = t0 + Duration.ofMinutes(min)

    @Test
    fun `LIVE 성공 - 포트 이벤트만으로 정상 기록 1건이 서버로 간다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        targets.value = listOf(target)
        assertIs<SessionState.Approaching>(coordinator.state.value)

        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5) // 예약된 Tick(도착 + 5분)
        assertIs<SessionState.Monitoring>(coordinator.state.value)

        soc.emit(SocSample(43, at(7)))
        assertIs<SessionState.Charging>(coordinator.state.value)
        assertEquals(listOf(ChargeResult.SUCCESS), uploaded.map { it.result })

        soc.emit(SocSample(80, at(50)))
        assertIs<SessionState.Completed>(coordinator.state.value)
    }

    @Test
    fun `LIVE 고장 - SOC가 그대로면 질문하고, 응이면 고장 기록`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5)
        now.value = at(15) // 급속 정체 10분
        assertIs<SessionState.Asking>(coordinator.state.value)
        assertEquals(ChargeSessionMachine.QUESTION_LIVE, coordinator.question.value?.question)

        coordinator.answer(VoiceIntent.YES)
        assertIs<SessionState.Failed>(coordinator.state.value)
        assertEquals(listOf(ChargeResult.FAIL), uploaded.map { it.result })
        assertEquals(null, coordinator.question.value)

        // 실패 기록 뒤 "몇 번 충전기였나요?" → 02번을 고르면 같은 기록에 번호가 붙는다
        val pick = assertNotNull(coordinator.chargerPick.value)
        assertEquals(listOf("01", "02", "03"), pick.chargers.map { it.chargerId })
        coordinator.pickCharger("02")
        assertEquals(listOf(uploaded.single().eventId to "02"), attached)
        assertNull(coordinator.chargerPick.value)
    }

    @Test
    fun `도착 뒤 잔량 신호가 하나도 없어도(충전 안 됨) 지금 잔량을 읽어 판정한다`() = runTest(UnconfinedTestDispatcher()) {
        // 실제 차: 잔량 신호는 값이 바뀔 때만 온다. 꽂아도 안 되면 도착 뒤 신호가 없다(에뮬레이터에서 발견)
        socNow = 42
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        now.value = at(5)
        assertIs<SessionState.Monitoring>(coordinator.state.value)
        now.value = at(15)
        assertIs<SessionState.Asking>(coordinator.state.value)
    }

    @Test
    fun `충전기 번호를 모르면 건너뛸 수 있다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5)
        now.value = at(15)
        coordinator.answer(VoiceIntent.YES)
        coordinator.pickCharger(null)
        assertEquals(emptyList(), attached)
        assertNull(coordinator.chargerPick.value)
    }

    @Test
    fun `정상 기록 뒤에는 충전기 번호를 묻지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5)
        soc.emit(SocSample(43, at(7)))
        assertNull(coordinator.chargerPick.value)
    }

    @Test
    fun `RETRO 성공 - 꺼진 동안 SOC가 오르면 다시 켤 때 정상 기록, 꺼진 동안 이벤트는 버린다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5)
        assertIs<SessionState.Monitoring>(coordinator.state.value)

        coordinator.appStopped()
        assertIs<SessionState.Suspended>(coordinator.state.value)
        assertEquals(false, coordinator.running.value)
        soc.emit(SocSample(60, at(30))) // 앱이 꺼져 있어 못 받음
        now.value = at(30)
        assertIs<SessionState.Suspended>(coordinator.state.value)
        assertEquals(emptyList(), uploaded)

        socNow = 65
        now.value = at(45)
        coordinator.appResumed()
        assertIs<SessionState.Charging>(coordinator.state.value)
        val e = uploaded.single()
        assertEquals(ChargeResult.SUCCESS to DetectMode.RETRO, e.result to e.mode)
        assertEquals(42 to 65, e.socStart to e.socEnd)
    }

    @Test
    fun `RETRO 고장 - 다시 켰는데 SOC 그대로면 사후 질문, 응이면 고장 기록`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5)
        coordinator.appStopped()

        socNow = 42
        now.value = at(40)
        coordinator.appResumed()
        assertIs<SessionState.AskingRetro>(coordinator.state.value)
        assertEquals(ChargeSessionMachine.questionRetro(station.name), coordinator.question.value?.question)

        coordinator.answer(VoiceIntent.YES)
        val e = uploaded.single()
        assertEquals(ChargeResult.FAIL to DetectMode.RETRO, e.result to e.mode)
    }

    @Test
    fun `떠난 뒤 새 목적지가 생기면 새 충전소로 세션을 시작한다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5)
        now.value = at(15)
        coordinator.answer(VoiceIntent.YES)
        locations.emit(GeoPoint(station.location.lat + 0.003, station.location.lon)) // 약 330m
        assertIs<SessionState.Ended>(coordinator.state.value)

        targets.value = listOf(target)
        assertIs<SessionState.Approaching>(coordinator.state.value)
    }

    // ------------------------------------------------------------ 잔량 변화 없이 출발 → 확인

    /** 도착 → 42% → 3분 머묾(세션 시작 전) → 출발. 꽂아 봤는데 안 돼서 떠나는 흔한 경우. */
    private suspend fun departWithoutCharge() {
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(3)
        parked.value = false
    }

    @Test
    fun `출발할 때 숨겨 보관하고 다음 주차에서 확인하면 FAIL과 충전기 번호 질문`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        departWithoutCharge()
        val c = assertNotNull(coordinator.confirm.value)
        assertEquals(false, c.visible)
        assertEquals(false, c.askedAgain)
        assertEquals("아까 강남 급속충전소에서 충전이 안 됐나요?", c.question)
        assertEquals(emptyList(), uploaded)

        parked.value = true
        assertEquals(true, assertNotNull(coordinator.confirm.value).visible)
        coordinator.answerConfirm(yes = true)
        assertNull(coordinator.confirm.value)
        assertEquals(listOf(ChargeResult.FAIL to DetectMode.LIVE), uploaded.map { it.result to it.mode })
        assertEquals("강남 급속충전소", assertNotNull(coordinator.chargerPick.value).stationName)
    }

    @Test
    fun `아니라고 하면 기록하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        departWithoutCharge()
        parked.value = true
        coordinator.answerConfirm(yes = false)
        assertNull(coordinator.confirm.value)
        assertEquals(emptyList(), uploaded)
    }

    @Test
    fun `주행 중에는 처음부터 질문하지 않고 다음 주차 때 한 번만 묻는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        departWithoutCharge()
        assertEquals(false, assertNotNull(coordinator.confirm.value).visible)
        now.value = at(3) + Duration.ofSeconds(50)
        assertEquals(false, assertNotNull(coordinator.confirm.value).visible)

        now.value = at(20)
        parked.value = true // 다음 충전소에 주차
        val again = assertNotNull(coordinator.confirm.value)
        assertEquals(true, again.visible && again.askedAgain)

        coordinator.answerConfirm(yes = true)
        assertEquals(1, uploaded.size)
    }

    @Test
    fun `주행 중 보류한 확인은 늦게 온 응답으로 실패를 기록하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        departWithoutCharge()

        coordinator.answerConfirm(yes = true)
        coordinator.answerConfirm(yes = false)
        assertEquals(emptyList(), uploaded)
        assertEquals(false, assertNotNull(coordinator.confirm.value).visible)

        parked.value = true
        coordinator.answerConfirm(yes = true)
        assertEquals(1, uploaded.size)
    }

    @Test
    fun `다시 물었는데도 답 없이 출발하면 기록하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        departWithoutCharge()
        now.value = at(4)
        parked.value = true
        parked.value = false
        assertNull(coordinator.confirm.value)
        assertEquals(emptyList(), uploaded)
    }

    @Test
    fun `2시간이 지난 확인은 버린다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        departWithoutCharge()
        now.value = at(3 + 121)
        assertNull(coordinator.confirm.value)
    }

    @Test
    fun `만료 뒤 도착한 응답도 실패를 기록하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        departWithoutCharge()
        parked.value = true
        now.value = at(3 + 121)
        coordinator.answerConfirm(yes = true)
        assertNull(coordinator.confirm.value)
        assertEquals(emptyList(), uploaded)
    }

    @Test
    fun `주차 신호 없는 시연은 즉시 확인하고 응답하는 동작을 유지한다`() = runTest(UnconfinedTestDispatcher()) {
        val demo = newCoordinator(withParkedSignal = false)
        demo.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(3)
        demo.dispatch(SessionInput.Departing(now.value))
        assertEquals(true, assertNotNull(demo.confirm.value).visible)

        demo.answerConfirm(yes = true)
        assertEquals(listOf(ChargeResult.FAIL), uploaded.map { it.result })
    }

    @Test
    fun `상세 카드에서 직접 표시하면 MANUAL 실패 기록`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        socNow = 30
        assertEquals(true, coordinator.reportFail(station))
        val e = uploaded.single()
        assertEquals(Triple(ChargeResult.FAIL, DetectMode.MANUAL, 30), Triple(e.result, e.mode, e.socEnd))
        assertNotNull(coordinator.chargerPick.value)
    }

    // ---------------------------------------------------------------- 내비 없이 충전 (기획 §5)

    @Test
    fun `내비 없이 충전구를 열고 잔량이 오르면 정상 기록`() = runTest(UnconfinedTestDispatcher()) {
        here = GeoPoint(37.4983, 127.0276) // 충전소에서 약 45m
        socNow = 42
        coordinator.start(backgroundScope, now)
        port.emit(true)
        assertIs<SessionState.Candidate>(coordinator.state.value)
        now.value = at(5)
        soc.emit(SocSample(43, at(7)))
        assertEquals(listOf(ChargeResult.SUCCESS), uploaded.map { it.result })
        assertEquals(station.id, uploaded.single().stationId)
    }

    @Test
    fun `충전구를 열어도 80m 안에 충전소가 없으면 시작하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        here = GeoPoint(37.5000, 127.0276) // 약 230m
        coordinator.start(backgroundScope, now)
        port.emit(true)
        assertIs<SessionState.Idle>(coordinator.state.value)
    }

    @Test
    fun `충전구를 열고 잔량 그대로 떠나면 확인을 묻는다`() = runTest(UnconfinedTestDispatcher()) {
        here = station.location
        socNow = 42
        coordinator.start(backgroundScope, now)
        port.emit(true)
        now.value = at(3)
        parked.value = false
        assertEquals("아까 강남 급속충전소에서 충전이 안 됐나요?", assertNotNull(coordinator.confirm.value).question)
    }

    @Test
    fun `충전구 신호가 있는 차가 충전구를 안 열고 떠나면 묻지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        port.emit(false) // 신호는 오지만 닫혀 있음
        departWithoutCharge() // 내비로 충전소에 도착했다가 그냥 떠남
        assertNull(coordinator.confirm.value)
    }

    @Test
    fun `가까운 충전소가 둘이면 성공은 가까운 곳으로, 실패는 묻지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        known = listOf(station, nextDoor)
        here = station.location
        socNow = 42
        coordinator.start(backgroundScope, now)
        port.emit(true)
        assertEquals(station, (coordinator.state.value as SessionState.Candidate).station)
        now.value = at(3)
        parked.value = false
        assertNull(coordinator.confirm.value)
    }

    @Test
    fun `같은 충전소에서 아니요를 두 번 답하면 그다음부터 묻지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        here = station.location
        socNow = 42
        coordinator.start(backgroundScope, now)
        repeat(3) { i ->
            parked.value = true
            port.emit(false)
            port.emit(true)
            now.value = at(10L * i + 3)
            parked.value = false
            if (i < 2) {
                parked.value = true
                coordinator.answerConfirm(yes = false)
                parked.value = false
            } else assertNull(coordinator.confirm.value)
            now.value = at(10L * i + 8)
        }
    }

    @Test
    fun `앱 프로세스가 죽었다 다시 켜져도 충전 전 잔량과 비교해 정상 기록을 남긴다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope, now)
        arrivals.emit(target)
        soc.emit(SocSample(42, t0))
        now.value = at(5)
        assertIs<SessionState.Monitoring>(coordinator.state.value)
        assertEquals(42, store.snap?.soc) // 도착 스냅샷이 영구 저장소에 있다

        // 시동이 꺼져 프로세스가 통째로 사라짐 → 40분 뒤 새 프로세스. 그사이 충전돼 60%
        coordinator.stop()
        now.value = at(45)
        socNow = 60
        val restarted = newCoordinator()
        restarted.start(backgroundScope, now)
        restarted.restore()
        assertEquals(listOf(ChargeResult.SUCCESS), uploaded.map { it.result })
        assertEquals(DetectMode.RETRO, uploaded.single().mode)
        assertIs<SessionState.Charging>(restarted.state.value)
    }

    @Test
    fun `저장된 스냅샷이 없으면 복원하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.restore()
        assertIs<SessionState.Idle>(coordinator.state.value)
        assertEquals(emptyList<ChargeEvent>(), uploaded)
    }
}
