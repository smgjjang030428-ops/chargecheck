package com.chargecheck.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.chargecheck.adapter.android.CarUxRestrictionsAdapter
import com.chargecheck.adapter.pleos.PleosNaviAdapter
import com.chargecheck.adapter.pleos.PleosVehicleAdapter
import com.chargecheck.adapter.remote.RemoteApiAdapter
import com.chargecheck.core.config.ChargeCheckConfig
import com.chargecheck.core.model.NearbyStation
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.Place
import com.chargecheck.core.model.RoutePlan
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.ClockPort
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.feature.session.ChargeSessionMachine
import com.chargecheck.feature.session.SessionCoordinator
import com.chargecheck.feature.session.SessionInput
import com.chargecheck.feature.session.SessionState
import com.chargecheck.feature.warning.ArrivalWarningCoordinator
import com.chargecheck.feature.warning.reasonText
import com.chargecheck.ui.MapStation
import com.chargecheck.ui.SearchUi
import com.chargecheck.ui.StationMapScreen
import com.chargecheck.ui.WarningUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 개발 PC에서 돌리는 서버. 에뮬레이터의 10.0.2.2 = PC의 127.0.0.1. 배포 서버가 생기면 release 값만 바꾼다. */
private const val DEV_SERVER = "http://10.0.2.2:8000"

/** 지도 기본 위치(강남역). 차 주변 5km에 충전소가 없으면 여기를 보여준다. */
private val DEFAULT_AREA = GeoPoint(37.4979, 127.0276)

/**
 * real flavor: Pleos Vehicle·NaviHelper·CarUxRestrictions 어댑터와 ChargeCheck 서버(RemoteApiAdapter)를
 * SessionCoordinator·ArrivalWarningCoordinator에 연결한다.
 */
private class RealGraph(context: Context) {
    val clock = object : ClockPort {
        override fun now(): Instant = Instant.now()
    }
    val vehicle = PleosVehicleAdapter(context, clock)
    val drivingState = CarUxRestrictionsAdapter(context)
    val navi = PleosNaviAdapter(context)
    val api = RemoteApiAdapter(DEV_SERVER, deviceId(context))

    /** 서버에서 받은 주변 충전소와 판정. 지도와 충전소 판별(resolve)이 같이 쓴다. */
    val stations = MutableStateFlow<List<NearbyStation>>(emptyList())

    /** 디버그 빌드는 에뮬레이터 확인용으로 판정 시간을 줄인다(정차 30초, 급속 정체 2분). */
    val devTools = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val config = if (devTools) {
        ChargeCheckConfig(
            onsiteMinStop = Duration.ofSeconds(30),
            stallTimeoutFast = Duration.ofMinutes(2),
            stallTimeoutSlow = Duration.ofMinutes(3),
            waitExtension = Duration.ofMinutes(1),
            retroMinElapsed = Duration.ofMinutes(2),
        )
    } else {
        ChargeCheckConfig.DEFAULT
    }

    /** §4.8 StationResolver 자리: POI ID가 같거나 100m 이내인 충전소. */
    val resolve: (RouteTarget) -> Station? = { target ->
        val known = stations.value.map { it.station }
        known.firstOrNull { it.id.value == target.poiId }
            ?: known.minByOrNull { it.location.distanceMetersTo(target.location) }
                ?.takeIf { it.location.distanceMetersTo(target.location) <= 100 }
    }

    val session = SessionCoordinator(
        machine = ChargeSessionMachine(DeviceId(UUID.randomUUID().toString()), { UUID.randomUUID().toString() }, config),
        vehicle = vehicle,
        navi = navi,
        location = navi,
        api = api,
        clock = clock,
        resolveStation = resolve,
    )
    val warning = ArrivalWarningCoordinator(navi, api, navi, resolve, log = session::note)

    val time = flow {
        while (true) {
            emit(Instant.now())
            delay(5_000)
        }
    }

    /** 차 주변 5km, 없으면 기본 지역의 충전소와 판정을 서버에서 받아온다. */
    suspend fun refreshStations() {
        val car = navi.lastKnown()
        val near = car?.let { api.nearbyWithStatus(it, 5_000).getOrNull() }.orEmpty()
        val list = near.ifEmpty { api.nearbyWithStatus(DEFAULT_AREA, 5_000).getOrNull().orEmpty() }
        if (list.isNotEmpty() || stations.value.isEmpty()) stations.value = list
    }

    /** 지역·장소 검색. 내 차 근처(없으면 기본 지역) 기준으로 충전소 결과를 가까운 순으로. */
    suspend fun search(query: String): SearchUi {
        val q = query.trim()
        if (q.isEmpty()) return SearchUi()
        return api.searchPlaces(q, navi.lastKnown() ?: DEFAULT_AREA).fold(
            onSuccess = { SearchUi(q, it, if (it.isEmpty()) "'$q' 검색 결과가 없어요" else null) },
            onFailure = { SearchUi(q, emptyList(), "검색 서버에 연결하지 못했어요") },
        )
    }

    /** 목적지까지 가는 길의 충전소. 차의 현재 위치·남은 주행 가능 거리·배터리를 함께 보낸다. */
    suspend fun planRoute(to: Place): Result<RoutePlan> {
        val from = navi.lastKnown() ?: DEFAULT_AREA
        val range = vehicle.remainingRangeKm()
        val soc = vehicle.currentSoc()
        session.note("경로 충전소 요청: ${to.name} · 남은 주행 ${range?.let { "%.0fkm".format(it) } ?: "모름"} · 배터리 ${soc ?: "모름"}%")
        return api.alongRoute(from, to, range, soc)
    }

    /** 기본 내비에 목적지를 넘기고, 충전소를 고르면 경유지로 넣는다. */
    suspend fun go(plan: RoutePlan, viaId: String?) {
        val dest = plan.destination
        val ok = navi.setDestination(RouteTarget(dest.name, dest.location, dest.stationId?.value)).isSuccess
        session.note("내비 목적지 요청: ${dest.name}" + if (ok) "" else " — 실패")
        val via = plan.candidates.firstOrNull { it.stop.station.id.value == viaId }?.stop?.station ?: return
        delay(1_500) // 목적지 경로 계산이 시작된 뒤에 경유지를 넣는다
        val added = navi.addWaypoint(RouteTarget(via.name, via.location, via.id.value)).isSuccess
        session.note("경유지 추가: ${via.name}" + if (added) "" else " — 실패")
    }

    fun release() {
        session.stop()
        warning.stop()
        vehicle.release()
        navi.release()
    }

    private fun deviceId(context: Context): DeviceId {
        // 설치마다 한 번 만드는 임의 UUID. 서버에는 해시로만 저장된다(명세 §5.3).
        val prefs = context.getSharedPreferences("chargecheck", Context.MODE_PRIVATE)
        val id = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
        return DeviceId(id)
    }
}

@Composable
fun FlavorContent() {
    val context = LocalContext.current
    val graph = remember { RealGraph(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf(SearchUi()) }
    var plan by remember { mutableStateOf<RoutePlan?>(null) }

    LaunchedEffect(Unit) {
        graph.session.start(this, graph.time)
        graph.warning.start(this)
        while (true) {
            graph.refreshStations()
            delay(30_000)
        }
    }
    DisposableEffect(Unit) { onDispose { graph.release() } }

    val state by graph.session.state.collectAsState()
    val question by graph.session.question.collectAsState()
    val chargerPick by graph.session.chargerPick.collectAsState()
    val toast by graph.session.toast.collectAsState()
    val log by graph.session.log.collectAsState()
    val warning by graph.warning.warning.collectAsState()
    val stations by graph.stations.collectAsState()
    val parked by remember { graph.drivingState.isParked() }.collectAsState(initial = true)
    val car by remember { graph.navi.locations() }.collectAsState(initial = null)

    // 경로를 보고 있으면 경로 위 충전소를, 아니면 주변 충전소를 지도에 찍는다
    val shown = plan?.let { p ->
        p.candidates.map { c ->
            val (s, info, st) = c.stop
            MapStation(s.id.value, s.name, s.location, st.verdict, st.message.ifBlank { reasonText(st.reasonCode) }, info, st.chargers, highlight = s.id == p.recommendedId)
        }
    } ?: stations.map { (s, info, st) ->
        MapStation(s.id.value, s.name, s.location, st.verdict, st.message.ifBlank { reasonText(st.reasonCode) }, info, st.chargers)
    }

    StationMapScreen(
        stations = shown,
        car = car,
        selectedId = selected,
        parked = parked,
        state = state,
        question = question,
        warning = warning?.let { WarningUi(it.station.name, it.status.verdict, it.reasonText, it.alternative?.name) },
        toast = toast,
        log = log,
        uploads = emptyList(),
        devTools = graph.devTools,
        onSelect = { selected = it },
        onNavigate = { id ->
            val p = plan
            selected = null
            if (p != null) {
                plan = null
                scope.launch { graph.go(p, id) }
                return@StationMapScreen
            }
            val s = stations.first { it.station.id.value == id }.station
            scope.launch {
                val ok = graph.navi.setDestination(RouteTarget(s.name, s.location, s.id.value)).isSuccess
                graph.session.note("내비 목적지 요청: ${s.name}" + if (ok) "" else " — 실패")
            }
        },
        onAnswer = { intent -> scope.launch { graph.session.answer(intent) } },
        onWarningChoice = { intent -> scope.launch { graph.warning.choose(intent) } },
        onDevArrive = {
            val target = (state as? SessionState.Approaching)?.station
            scope.launch {
                if (target != null) {
                    graph.session.note("개발용: ${target.name} 도착 처리")
                    graph.session.dispatch(SessionInput.Arrived(target, Instant.now()))
                } else {
                    graph.session.note("개발용: 목적지 충전소가 없어 도착 처리를 건너뜀")
                }
            }
        },
        chargerPick = chargerPick,
        onPickCharger = { id ->
            scope.launch {
                graph.session.pickCharger(id)
                graph.refreshStations()
            }
        },
        search = search,
        onQueryChange = { search = search.copy(query = it) },
        onSearch = {
            val q = search.query
            search = search.copy(results = emptyList(), message = "검색 중…")
            scope.launch { search = graph.search(q) }
        },
        onPickPlace = { place ->
            search = SearchUi(query = place.name, message = "가는 길의 충전소를 찾는 중…")
            selected = null
            scope.launch {
                graph.planRoute(place).fold(
                    onSuccess = { plan = it; search = SearchUi(query = place.name) },
                    onFailure = { search = SearchUi(query = place.name, message = "경로를 받지 못했어요. 잠시 후 다시 시도해 주세요") },
                )
            }
        },
        plan = plan,
        onRouteGo = { id ->
            val p = plan ?: return@StationMapScreen
            plan = null
            selected = null
            search = SearchUi()
            scope.launch { graph.go(p, id) }
        },
        onRouteClose = { plan = null; selected = null; search = SearchUi() },
    )
}
