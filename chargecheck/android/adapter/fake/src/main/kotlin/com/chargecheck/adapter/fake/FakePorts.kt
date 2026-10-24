package com.chargecheck.adapter.fake

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargerStatus
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.PublicState
import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.ReviewTag
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationStatus
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.ChargeCheckApiPort
import com.chargecheck.core.port.ClockPort
import com.chargecheck.core.port.LocationPort
import com.chargecheck.core.port.NaviPort
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.core.port.VehiclePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

// 시연·테스트용 가짜 포트. ScenarioPlayer가 값을 흘려보낸다. (기획문서/앱구조_명세서_AI용.md §4.14, §4.15)

/** 배속으로 흐르는 시계. */
class FakeClock(start: Instant) : ClockPort {
    private val _time = MutableStateFlow(start)
    val time: StateFlow<Instant> = _time.asStateFlow()
    override fun now(): Instant = _time.value
    fun set(now: Instant) { _time.value = now }
}

class FakeVehicle : VehiclePort {
    private val samples = MutableSharedFlow<SocSample>(replay = 1, extraBufferCapacity = 64)
    private var last: Int? = null
    var targetPct: Int? = null

    override fun soc(): Flow<SocSample> = samples
    override suspend fun currentSoc(): Int? = last
    override suspend fun chargeTargetPct(): Int? = targetPct

    suspend fun emitSoc(sample: SocSample) {
        last = sample.pct
        samples.emit(sample)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clear() {
        last = null
        samples.resetReplayCache()
    }
}

class FakeNavi : NaviPort {
    private val targets = MutableStateFlow<List<RouteTarget>>(emptyList())
    private val arrived = MutableSharedFlow<RouteTarget>(extraBufferCapacity = 8)
    val waypoints = mutableListOf<RouteTarget>()

    override fun routeTargets(): Flow<List<RouteTarget>> = targets
    override fun arrivals(): Flow<RouteTarget> = arrived
    /** 경유지는 목적지보다 먼저 들르므로 맨 앞에 넣는다. */
    override suspend fun addWaypoint(target: RouteTarget): Result<Unit> {
        waypoints += target
        targets.value = listOf(target) + targets.value
        return Result.success(Unit)
    }

    override suspend fun setDestination(target: RouteTarget): Result<Unit> {
        targets.value = listOf(target)
        return Result.success(Unit)
    }

    fun setTargets(list: List<RouteTarget>) { targets.value = list }
    suspend fun arrive(target: RouteTarget) = arrived.emit(target)
}

class FakeLocation : LocationPort {
    private val points = MutableSharedFlow<GeoPoint>(extraBufferCapacity = 8)
    private var last: GeoPoint? = null

    override fun locations(): Flow<GeoPoint> = points
    override suspend fun lastKnown(): GeoPoint? = last

    suspend fun moveTo(p: GeoPoint) {
        last = p
        points.emit(p)
    }
}

/** 서버 대신 받은 이벤트를 모아 두고, 시나리오가 정한 충전소 판정을 돌려준다. */
class FakeApi : ChargeCheckApiPort {
    private val _events = MutableStateFlow<List<ChargeEvent>>(emptyList())
    val events: StateFlow<List<ChargeEvent>> = _events.asStateFlow()

    private var stations: List<Station> = emptyList()
    private var verdicts: Map<StationId, Pair<Verdict, ReasonCode>> = emptyMap()

    fun configure(stations: List<Station>, verdicts: Map<StationId, Pair<Verdict, ReasonCode>>) {
        this.stations = stations
        this.verdicts = verdicts
    }

    override suspend fun postEvent(event: ChargeEvent): Result<Unit> {
        _events.update { it + event }
        return Result.success(Unit)
    }

    override suspend fun attachCharger(eventId: String, chargerId: String): Result<Unit> {
        _events.update { list -> list.map { if (it.eventId == eventId) it.copy(chargerId = chargerId) else it } }
        return Result.success(Unit)
    }

    override suspend fun stationStatus(id: StationId): Result<StationStatus> = Result.success(status(id))

    override suspend fun nearbyStations(at: GeoPoint, radiusM: Int): Result<List<Station>> = Result.success(stations)

    /** 같은 충전소를 뺀 나머지를 가까운 순으로. */
    override suspend fun alternatives(id: StationId, at: GeoPoint): Result<List<Pair<Station, StationStatus>>> =
        Result.success(
            stations.filter { it.id != id }
                .sortedBy { it.location.distanceMetersTo(at) }
                .map { it to status(it.id) },
        )

    override suspend fun postReview(stationId: StationId, tags: List<ReviewTag>): Result<Unit> = Result.success(Unit)

    fun clear() { _events.value = emptyList() }

    /** 시연용 충전소는 모두 충전기 4대(01~04). 판정은 시나리오가 정한다. */
    private fun status(id: StationId): StationStatus {
        val (verdict, reason) = verdicts[id] ?: (Verdict.GRAY to ReasonCode.NO_RECENT_DATA)
        val fails = if (verdict == Verdict.RED) 3 else 0
        val type = stations.firstOrNull { it.id == id }?.chargerType ?: ChargerType.FAST
        val chargers = (1..4).map { n ->
            ChargerStatus("%02d".format(n), type, PublicState.AVAILABLE, if (verdict == Verdict.RED) Verdict.RED else Verdict.GRAY, fails)
        }
        val ok = if (verdict == Verdict.RED) 0 else 4
        return StationStatus(id, verdict, reason, null, fails, 4, 4, emptyList(), okCount = ok, chargers = chargers)
    }
}
