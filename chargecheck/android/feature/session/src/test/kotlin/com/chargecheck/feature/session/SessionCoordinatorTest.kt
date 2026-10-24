package com.chargecheck.feature.session

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
    private val uploaded = mutableListOf<ChargeEvent>()
    private var socNow: Int? = null
    private val attached = mutableListOf<Pair<String, String>>()
    private val threeChargers = (1..3).map {
        ChargerStatus("0$it", ChargerType.FAST, PublicState.AVAILABLE, Verdict.GRAY, 0)
    }

    private val coordinator = SessionCoordinator(
        machine = ChargeSessionMachine(DeviceId("test"), { "e${uploaded.size}" }),
        vehicle = object : VehiclePort {
            override fun soc() = soc
            override suspend fun currentSoc(): Int? = socNow
            override suspend fun chargeTargetPct(): Int? = 80
        },
        navi = object : NaviPort {
            override fun routeTargets() = targets
            override fun arrivals() = arrivals
            override suspend fun addWaypoint(target: RouteTarget) = Result.success(Unit)
            override suspend fun setDestination(target: RouteTarget) = Result.success(Unit)
        },
        location = object : LocationPort {
            override fun locations() = locations
            override suspend fun lastKnown(): GeoPoint? = null
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
    )

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
}
