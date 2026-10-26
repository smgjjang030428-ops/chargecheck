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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalWarningCoordinatorTest {

    private val red = Station(StationId("R"), "서초 급속충전소", GeoPoint(37.4919, 127.0076), ChargerType.FAST)
    private val green = Station(StationId("G"), "강남 급속충전소", GeoPoint(37.4979, 127.0276), ChargerType.FAST)
    private val verdicts = mapOf(red.id to Verdict.RED, green.id to Verdict.GREEN)

    private val targets = MutableStateFlow<List<RouteTarget>>(emptyList())
    private val waypoints = mutableListOf<RouteTarget>()
    private val replaced = mutableListOf<Pair<Int, String?>>()
    private var statusCalls = 0

    private fun Station.target() = RouteTarget(name, location, id.value)

    private val coordinator = ArrivalWarningCoordinator(
        navi = object : NaviPort {
            override fun routeTargets() = targets
            override fun arrivals() = emptyFlow<RouteTarget>()
            override suspend fun replaceWaypoint(index: Int, target: RouteTarget): Result<Unit> {
                replaced += index to target.poiId
                return Result.success(Unit)
            }
            override suspend fun addWaypoint(target: RouteTarget): Result<Unit> {
                waypoints += target
                targets.value = listOf(target) + targets.value
                return Result.success(Unit)
            }
            override suspend fun setDestination(target: RouteTarget): Result<Unit> {
                targets.value = listOf(target)
                return Result.success(Unit)
            }
        },
        api = object : ChargeCheckApiPort {
            override suspend fun postEvent(event: ChargeEvent) = Result.success(Unit)
            override suspend fun attachCharger(eventId: String, chargerId: String) = Result.success(Unit)
            override suspend fun stationStatus(id: StationId): Result<StationStatus> {
                statusCalls++
                return Result.success(status(id))
            }
            override suspend fun nearbyStations(at: GeoPoint, radiusM: Int) = Result.success(listOf(red, green))
            override suspend fun alternatives(id: StationId, at: GeoPoint) =
                Result.success(listOf(red, green).filter { it.id != id }.map { it to status(it.id) })
            override suspend fun postReview(stationId: StationId, tags: List<ReviewTag>) = Result.success(Unit)
        },
        location = object : LocationPort {
            override fun locations() = emptyFlow<GeoPoint>()
            override suspend fun lastKnown(): GeoPoint? = null
        },
        resolveStation = { t -> listOf(red, green).firstOrNull { it.id.value == t.poiId } },
    )

    private fun status(id: StationId) =
        StationStatus(id, verdicts.getValue(id), ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS, null, 3, null, null, emptyList())

    @Test
    fun `목적지가 빨강이면 경고하고, 바꾸기를 고르면 초록 충전소를 경유지로 넣는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target())
        val w = assertNotNull(coordinator.warning.value)
        assertEquals(green, w.alternative)
        assertEquals(Verdict.GREEN, w.alternativeStatus?.verdict) // 대안에도 판정 근거를 붙인다

        coordinator.choose(VoiceIntent.SWITCH)
        assertNull(coordinator.warning.value)
        assertEquals(listOf(green.id.value), waypoints.map { it.poiId })
    }

    @Test
    fun `내비가 경유지로 넣은 충전소도 검사한다(목적지가 충전소가 아니어도)`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target(), RouteTarget("수원역", GeoPoint(37.2664, 127.0001), null))
        val w = assertNotNull(coordinator.warning.value)
        assertEquals(red to 0, w.station to w.waypointIndex)
        // [바꾸기]면 그 경유지를 대안으로 교체(더하지 않는다)
        coordinator.choose(VoiceIntent.SWITCH)
        assertEquals(listOf<Pair<Int, String?>>(0 to green.id.value), replaced)
        assertEquals(emptyList<RouteTarget>(), waypoints)
    }

    @Test
    fun `초록이면 경고하지 않고, 같은 목적지는 다시 조회하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(green.target())
        assertNull(coordinator.warning.value)
        targets.value = listOf(green.target(), red.target())
        assertEquals(1, statusCalls)
    }

    @Test
    fun `일부 충전기만 실패면 경고하지 않고, 충전소 전체가 빨강이거나 1대짜리 실패면 경고한다`() {
        fun status(v: Verdict, r: ReasonCode) = StationStatus(StationId("X"), v, r, null, 1, 5, 6, emptyList())
        assertEquals(false, shouldWarn(status(Verdict.YELLOW, ReasonCode.SOME_CHARGERS_FAIL)))
        assertEquals(true, shouldWarn(status(Verdict.YELLOW, ReasonCode.FAIL_AFTER_SUCCESS)))
        assertEquals(true, shouldWarn(status(Verdict.RED, ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS)))
        assertEquals(false, shouldWarn(status(Verdict.GRAY, ReasonCode.NO_RECENT_DATA)))
    }

    @Test
    fun `그대로를 고르면 경유지를 넣지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        coordinator.start(backgroundScope)
        targets.value = listOf(red.target())
        coordinator.choose(VoiceIntent.KEEP)
        assertNull(coordinator.warning.value)
        assertEquals(emptyList<RouteTarget>(), waypoints)
    }
}
