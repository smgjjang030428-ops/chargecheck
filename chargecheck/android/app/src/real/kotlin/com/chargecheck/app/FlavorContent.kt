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

    StationMapScreen(
        stations = stations.map { (s, info, st) ->
            MapStation(s.id.value, s.name, s.location, st.verdict, st.message.ifBlank { reasonText(st.reasonCode) }, info, st.chargers)
        },
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
            val s = stations.first { it.station.id.value == id }.station
            selected = null
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
    )
}
