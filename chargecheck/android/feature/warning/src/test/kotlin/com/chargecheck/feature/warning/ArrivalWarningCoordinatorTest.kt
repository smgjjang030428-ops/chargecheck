package com.chargecheck.feature.warning

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.ReviewTag
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationStatus
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.port.ChargeCheckApiPort
import com.chargecheck.core.port.LocationPort
import com.chargecheck.core.port.NaviPort
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.core.port.VoiceIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalWarningCoordinatorTest {

    private val red = Station(StationId("R"), "서초 급속충전소", GeoPoint(37.4919, 127.0076), ChargerType.FAST)
    private val green = Station(StationId("G"), "강남 급속충전소", GeoPoint(37.4979, 127.0276), ChargerType.FAST)
    private val other = Station(StationId("O"), "역삼 급속충전소", GeoPoint(37.5000, 127.0360), ChargerType.FAST)
    private val verdicts = mutableMapOf(red.id to Verdict.RED, green.id to Verdict.GREEN, other.id to Verdict.RED)

    private val cityHall = RouteTarget("서울특별시청", GeoPoint(37.5663, 126.9779), null)
    private val cafe = RouteTarget("카페", GeoPoint(37.5100, 127.0100), null)

    /** 내비 경로(경유지들 + 목적지). 실제 내비처럼 교체 요청을 받으면 이 목록을 바꾼다. */
    private val targets = MutableStateFlow<List<RouteTarget>>(emptyList())
    private val cancellations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var naviEdits = 0
    private var statusCalls = 0
    /** 판정 조회를 이 횟수만큼 실패시킨다. */
    private var failStatus = 0
    /** 있으면 판정 응답을 이것이 끝날 때까지 미룬다(늦은 응답). */
    private var holdStatus: CompletableDeferred<Unit>? = null
    /** 내비가 교체 요청을 거절한다. */
    private var naviFails = false
    /** 대체 충전소 조회를 이 횟수만큼 실패시킨다. */
    private var failAlternatives = 0
    /** 내비가 요청은 받지만 경로를 바꾸지 않는다. */
    private var naviIgnores = false
    private var enabled = true

    private fun Station.target() = RouteTarget(name, location, id.value)
    private fun route() = targets.value.map { it.poiId ?: it.name }

    private fun edit(change: (MutableList<RouteTarget>) -> Unit): Result<Unit> {
        naviEdits++
        if (naviFails) return Result.failure(IllegalStateException("내비 거절"))
        if (!naviIgnores) targets.value = targets.value.toMutableList().also(change)
        return Result.success(Unit)
    }

    private val coordinator = ArrivalWarningCoordinator(
        navi = object : NaviPort {
            override fun routeTargets() = targets
            override fun routeCancellations() = cancellations
            override fun arrivals() = emptyFlow<RouteTarget>()
            override suspend fun replaceWaypoint(index: Int, target: RouteTarget) = edit { it[index] = target }
            override suspend fun addWaypoint(target: RouteTarget) = edit { it.add(0, target) }
            override suspend fun setDestination(target: RouteTarget) = edit { it.clear(); it += target }
            override suspend fun replaceDestination(target: RouteTarget, waypoints: List<RouteTarget>) =
                edit { it.clear(); it += waypoints; it += target }
        },
        api = object : ChargeCheckApiPort {
            override suspend fun postEvent(event: ChargeEvent) = Result.success(Unit)
            override suspend fun attachCharger(eventId: String, chargerId: String) = Result.success(Unit)
            override suspend fun stationStatus(id: StationId): Result<StationStatus> {
                statusCalls++
                holdStatus?.await()
                if (failStatus > 0) {
                    failStatus--
                    return Result.failure(IOException("서버 깨는 중"))
                }
                return Result.success(status(id))
            }
            override suspend fun nearbyStations(at: GeoPoint, radiusM: Int) = Result.success(listOf(red, green))
            override suspend fun alternatives(id: StationId, at: GeoPoint): Result<List<Pair<Station, StationStatus>>> {
                if (failAlternatives > 0) {
                    failAlternatives--
                    return Result.failure(IOException("시간 초과"))
                }
                return Result.success(listOf(red, green).filter { it.id != id }.map { it to status(it.id) })
            }
            override suspend fun postReview(stationId: StationId, tags: List<ReviewTag>) = Result.success(Unit)
        },
        location = object : LocationPort {
            override fun locations() = emptyFlow<GeoPoint>()
            override suspend fun lastKnown(): GeoPoint? = null
        },
        resolveStation = { t -> listOf(red, green, other).firstOrNull { it.id.value == t.poiId } },
        enabled = { enabled },
    )

    private fun status(id: StationId) =
        StationStatus(id, verdicts.getValue(id), ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS, null, 3, null, null, emptyList())

    // ── 바꾸기: 경고한 충전소 자리만 바꾼다(R3) ──

    @Test
    fun `목적지가 빨강이면 경고하고, 바꾸기를 고르면 목적지를 초록 충전소로 바꾼다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target())
        val w = assertNotNull(coordinator.warning.value)
        assertEquals(green, w.alternative)
        assertEquals(Verdict.GREEN, w.alternativeStatus?.verdict) // 대안에도 판정 근거를 붙인다

        coordinator.choose(VoiceIntent.SWITCH)
        assertNull(coordinator.warning.value)
        assertEquals(listOf("G"), route()) // [A] → [B]. 고장 충전소가 목적지로 남지 않는다
        assertEquals("강남 급속충전소(으)로 바뀌었어요", coordinator.swapStatus.value)
    }

    @Test
    fun `경유지 A는 그 자리만 바꾸고 목적지는 그대로`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target(), cityHall)
        assertEquals(red to 0, assertNotNull(coordinator.warning.value).let { it.station to it.waypointIndex })
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("G", "서울특별시청"), route())
    }

    @Test
    fun `두 번째 경유지 A만 바꾸고 첫 경유지와 목적지·순서는 그대로`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(cafe, red.target(), cityHall)
        assertNotNull(coordinator.warning.value)
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("카페", "G", "서울특별시청"), route())
    }

    @Test
    fun `경유지가 있는 목적지 A는 목적지만 바꾸고 경유지는 남긴다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(cafe, red.target())
        assertNotNull(coordinator.warning.value)
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("카페", "G"), route())
    }

    @Test
    fun `경고할 때 목적지가 잠깐 비어 있었어도 누를 때 경유지면 그 경유지를 바꾼다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        // 경유지를 넣은 직후 내비가 경로를 다시 짜는 동안: 목적지 없이 경유지만 보인다
        targets.value = listOf(red.target())
        assertNull(assertNotNull(coordinator.warning.value).waypointIndex)
        targets.value = listOf(red.target(), cityHall)
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("G", "서울특별시청"), route())
    }

    @Test
    fun `경고 뒤 경로에서 A가 빠졌으면 오래된 순번으로 다른 곳을 바꾸지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target(), cityHall)
        assertNotNull(coordinator.warning.value)
        targets.value = listOf(cafe, cityHall) // 운전자가 내비에서 경로를 바꿈
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("카페", "서울특별시청"), route())
        assertNull(coordinator.warning.value)
        assertEquals(0, naviEdits)
    }

    @Test
    fun `내비가 요청을 거절하면 바뀌었다고 하지 않고 경고를 다시 띄워 다시 고를 수 있다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target(), cityHall)
        naviFails = true
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("R", "서울특별시청"), route())
        assertNotNull(coordinator.warning.value)
        assertEquals("경유지 바꾸기 실패 · 다시 눌러 주세요", coordinator.swapStatus.value)

        naviFails = false
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("G", "서울특별시청"), route())
    }

    @Test
    fun `요청은 보냈지만 경로가 그대로면 바뀌었다고 하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target(), cityHall)
        naviIgnores = true
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("R", "서울특별시청"), route())
        assertTrue(coordinator.swapStatus.value!!.startsWith("내비 경로가 바뀌었는지 확인하지 못했어요"))
        assertNotNull(coordinator.warning.value)
        naviIgnores = false
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf("G", "서울특별시청"), route())
        assertEquals(2, naviEdits)
    }

    @Test
    fun `확인 시간 초과 뒤 늦게 반영되면 다시 바꾸지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target(), cityHall)
        naviIgnores = true
        coordinator.choose(VoiceIntent.SWITCH)
        assertNotNull(coordinator.warning.value)
        targets.value = listOf(green.target(), cityHall) // 첫 요청이 뒤늦게 반영됨
        coordinator.choose(VoiceIntent.SWITCH)
        assertNull(coordinator.warning.value)
        assertEquals(listOf("G", "서울특별시청"), route())
        assertEquals(1, naviEdits)
    }

    @Test
    fun `반영을 기다리는 동안 다른 일반 목적지로 바꾸면 옛 경고를 복원하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target(), cityHall)
        naviIgnores = true
        backgroundScope.launch {
            delay(1_000)
            targets.value = listOf(cafe)
        }
        coordinator.choose(VoiceIntent.SWITCH)
        assertNull(coordinator.warning.value)
        assertNull(coordinator.routeCheck.value)
        assertEquals(listOf("카페"), route())
    }

    @Test
    fun `그대로를 고르면 경로를 바꾸지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target())
        coordinator.choose(VoiceIntent.KEEP)
        assertNull(coordinator.warning.value)
        assertEquals(listOf("R"), route())
    }

    // ── 판정 조회 실패 뒤 다시 조회(R2) ──

    @Test
    fun `판정 조회가 실패해도 경로가 그대로인 채 다시 조회해서 경고한다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        failStatus = 2
        targets.value = listOf(red.target(), cityHall)
        assertNull(coordinator.warning.value)
        advanceTimeBy(5_001) // 5초 뒤 재조회: 또 실패
        assertNull(coordinator.warning.value)
        advanceTimeBy(15_001) // 15초 뒤 재조회: 성공 → 빨강
        assertNotNull(coordinator.warning.value)
        assertEquals(3, statusCalls)
        advanceTimeBy(120_000) // 성공한 뒤에는 더 조회하지 않는다
        assertEquals(3, statusCalls)
    }

    @Test
    fun `초록이면 경고하지 않고, 같은 충전소는 다시 조회하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(green.target())
        assertNull(coordinator.warning.value)
        targets.value = listOf(green.target(), red.target())
        assertEquals(1, statusCalls)
    }

    @Test
    fun `재조회를 기다리는 동안 경로가 B로 바뀌면 A는 그만 조회하고 B를 검사한다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        verdicts[green.id] = Verdict.GREEN
        failStatus = 1
        targets.value = listOf(red.target(), cityHall)
        targets.value = listOf(green.target(), cityHall)
        assertEquals(2, statusCalls)
        advanceTimeBy(300_000)
        assertEquals(2, statusCalls) // A 재조회 없음
        assertNull(coordinator.warning.value)
    }

    @Test
    fun `충전소 없는 새 경로로 바꾸면 이전 경고와 재시도를 정리한다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        failStatus = 1
        targets.value = listOf(red.target(), cityHall)
        assertNotNull(coordinator.routeCheck.value)
        targets.value = listOf(cityHall)
        assertNull(coordinator.warning.value)
        assertNull(coordinator.routeCheck.value)
        advanceTimeBy(300_000)
        assertEquals(1, statusCalls)
        targets.value = listOf(red.target(), cityHall)
        assertNotNull(coordinator.warning.value) // 같은 충전소로 다시 설정하면 새 검사
        assertEquals(2, statusCalls)
    }

    @Test
    fun `일반 목적지로 변경한 뒤 도착한 이전 빨강 응답은 버린다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        val late = CompletableDeferred<Unit>()
        holdStatus = late
        targets.value = listOf(red.target(), cityHall)
        targets.value = listOf(cityHall)
        late.complete(Unit)
        assertNull(coordinator.warning.value)
        assertNull(coordinator.routeCheck.value)
    }

    @Test
    fun `명시적인 안내 취소는 빈 경로 중에도 재시도와 경고를 정리한다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        failStatus = 1
        targets.value = listOf(red.target(), cityHall)
        targets.value = emptyList()
        cancellations.emit(Unit)
        assertNull(coordinator.warning.value)
        assertNull(coordinator.routeCheck.value)
        advanceTimeBy(300_000)
        assertEquals(1, statusCalls)
        coordinator.recheck()
        assertEquals(1, statusCalls) // 취소된 옛 경로를 설정 재활성화로 되살리지 않음
    }

    @Test
    fun `A의 늦은 응답이 B의 결과를 덮어쓰지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        val late = CompletableDeferred<Unit>()
        holdStatus = late
        targets.value = listOf(red.target(), cityHall) // A 조회가 멈춰 있음
        holdStatus = null
        targets.value = listOf(green.target(), cityHall) // B는 바로 초록
        assertNull(coordinator.warning.value)
        late.complete(Unit) // 이제야 A(빨강) 응답
        assertNull(coordinator.warning.value)
    }

    @Test
    fun `같은 충전소를 동시에 두 번 알려도 조회는 한 번`() = runTest(UnconfinedTestDispatcher()) {
        val late = CompletableDeferred<Unit>()
        holdStatus = late
        val route = listOf(red.target(), cityHall)
        backgroundScope.launch { coordinator.onTargets(route) }
        backgroundScope.launch { coordinator.onTargets(route) }
        late.complete(Unit)
        assertEquals(1, statusCalls)
        assertNotNull(coordinator.warning.value)
    }

    @Test
    fun `경고를 끄면 재조회가 멈추고, 다시 켜면 지금 경로를 검사한다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        failStatus = 1
        targets.value = listOf(red.target(), cityHall)
        enabled = false
        advanceTimeBy(300_000)
        assertEquals(1, statusCalls)
        enabled = true
        coordinator.recheck()
        assertEquals(2, statusCalls)
        assertNotNull(coordinator.warning.value)
    }

    @Test
    fun `경로를 다시 짜는 동안 빈 목록이 와도 재조회를 이어 간다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        failStatus = 1
        targets.value = listOf(red.target(), cityHall)
        targets.value = emptyList()
        advanceTimeBy(5_001)
        assertNotNull(coordinator.warning.value)
    }

    @Test
    fun `대체 충전소 조회만 실패하면 경고는 먼저 띄우고 대안을 다시 찾아 붙인다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        failAlternatives = 1
        targets.value = listOf(red.target(), cityHall)
        val first = assertNotNull(coordinator.warning.value)
        assertNull(first.alternative)
        advanceTimeBy(5_001)
        assertEquals(green, coordinator.warning.value?.alternative)
        assertEquals(1, statusCalls) // 판정은 다시 조회하지 않음
    }

    @Test
    fun `일부 충전기만 실패면 경고하지 않고, 충전소 전체가 빨강이거나 1대짜리 실패면 경고한다`() {
        fun status(v: Verdict, r: ReasonCode) = StationStatus(StationId("X"), v, r, null, 1, 5, 6, emptyList())
        assertEquals(false, shouldWarn(status(Verdict.YELLOW, ReasonCode.SOME_CHARGERS_FAIL)))
        assertEquals(true, shouldWarn(status(Verdict.YELLOW, ReasonCode.FAIL_AFTER_SUCCESS)))
        assertEquals(true, shouldWarn(status(Verdict.RED, ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS)))
        assertEquals(false, shouldWarn(status(Verdict.GRAY, ReasonCode.NO_RECENT_DATA)))
    }
}
