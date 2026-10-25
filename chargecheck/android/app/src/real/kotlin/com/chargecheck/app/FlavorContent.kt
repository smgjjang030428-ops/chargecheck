package com.chargecheck.app

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.NearbyStation
import com.chargecheck.core.model.RoutePlan
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.feature.session.SessionInput
import com.chargecheck.feature.session.SessionState
import com.chargecheck.feature.session.stationId
import com.chargecheck.feature.warning.reasonText
import com.chargecheck.ui.MapStation
import com.chargecheck.ui.RouteCardUi
import com.chargecheck.ui.SearchUi
import com.chargecheck.ui.StationMapScreen
import com.chargecheck.ui.WarningUi
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/**
 * real flavor 화면. 상태는 [RealGraph](프로세스에 하나)가 갖고, 여기서는 화면에 그리고 버튼을 연결만 한다.
 * - 내비 목적지가 바뀌고 충전이 필요하면 RealGraph가 [RoutePlan]을 채운다 → 주차 중 패널, 주행 중 15초 카드.
 * - 검색창은 충전소·지역 찾기(우리 DB). 충전소가 안 나오면 [내비에서 목적지 찾기].
 */
@Composable
fun FlavorContent() {
    val context = LocalContext.current
    val graph = remember { RealGraph.get(context) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf(SearchUi()) }

    val state by graph.session.state.collectAsState()
    val question by graph.session.question.collectAsState()
    val chargerPick by graph.session.chargerPick.collectAsState()
    val toast by graph.session.toast.collectAsState()
    val log by graph.session.log.collectAsState()
    val warning by graph.warning.warning.collectAsState()
    val stations by graph.stations.collectAsState()
    val plan by graph.plan.collectAsState()
    val routeCardOnDriving by graph.routeCardOnDriving.collectAsState()
    val parked by remember { graph.drivingState.isParked() }.collectAsState(initial = true)
    val car by remember { graph.navi.locations() }.collectAsState(initial = null)
    val confirm by graph.session.confirm.collectAsState()

    // 경로 카드가 열려 있으면 경로 근처 충전소를, 아니면 주변 충전소를 지도에 찍는다
    val shown = plan?.let { p -> p.candidates.map { c -> c.stop.toMap(highlight = c.stop.station.id == p.recommendedId) } }
        ?: stations.map { it.toMap() }

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
        onSelect = { id ->
            selected = id
            if (id == null && plan == null) scope.launch { graph.clearFocus() }
        },
        onNavigate = { id ->
            selected = null
            val p = plan
            if (p != null) {
                // 경로 카드에서 고른 충전소 = 경유지로 들르기(목적지는 이미 내비에 있음)
                p.candidates.firstOrNull { it.stop.station.id.value == id }?.let { c -> scope.launch { graph.addStop(c.stop.station) } }
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
            search = search.copy(results = emptyList(), message = "찾는 중…", naviQuery = null)
            scope.launch { search = graph.search(q) }
        },
        onPickPlace = { place ->
            search = SearchUi(query = place.name)
            scope.launch {
                graph.focus(place)
                selected = place.stationId?.value
            }
        },
        onNaviSearch = { q ->
            search = SearchUi()
            scope.launch {
                val ok = graph.navi.openSearch(q).isSuccess
                graph.session.note("내비 검색 화면 요청: $q" + if (ok) "" else " — 실패")
            }
        },
        plan = plan,
        onRouteGo = { id ->
            val p = plan ?: return@StationMapScreen
            selected = null
            p.candidates.firstOrNull { it.stop.station.id.value == id }?.let { c -> scope.launch { graph.addStop(c.stop.station) } }
        },
        onRouteClose = {
            selected = null
            graph.dismissRoute()
        },
        confirm = confirm,
        onConfirm = { yes -> scope.launch { graph.session.answerConfirm(yes) } },
        // 지금 세션의 충전소이거나, 차가 150m 안에 있을 때만(현장에 있었던 차만 표시)
        canReport = { id ->
            state.stationId()?.value == id ||
                shown.firstOrNull { it.id == id }?.let { s -> car?.let { it.distanceMetersTo(s.location) <= 150 } } == true
        },
        onReportFail = { id ->
            val s = stations.firstOrNull { it.station.id.value == id }?.station
            selected = null
            if (s != null) scope.launch { graph.session.reportFail(s); graph.refreshStations() }
        },
        routeCard = plan?.takeIf { routeCardOnDriving }?.toCard(),
        onRouteCard = { go ->
            val p = plan
            val rec = p?.candidates?.firstOrNull { it.stop.station.id == p.recommendedId }
            if (go && rec != null) scope.launch { graph.addStop(rec.stop.station) } else graph.dismissRoute()
        },
        devExtra = if (graph.devTools) {
            // 운전자가 내비에 일반 목적지를 넣은 상황을 재현(에뮬레이터 내비는 자체 검색이 안 될 수 있음)
            listOf("목적지 바꾸기" to { scope.launch { graph.navi.setDestination(DEV_DESTINATIONS[devDestination++ % DEV_DESTINATIONS.size]) }; Unit }) +
                gleoMocks(context)
        } else {
            emptyList()
        },
    )
}

private fun NearbyStation.toMap(highlight: Boolean = false): MapStation {
    val reason = status.message.ifBlank { reasonText(status.reasonCode) } + (status.notice?.let { " · $it" } ?: "")
    return MapStation(station.id.value, station.name, station.location, status.verdict, reason, info, status.chargers, highlight)
}

/** 주행 중 15초 카드: "가는 길에 충전이 필요해요" + 추천 충전소 한 줄. */
private fun RoutePlan.toCard(): RouteCardUi {
    val rec = candidates.firstOrNull { it.stop.station.id == recommendedId }
        ?: return RouteCardUi("충전 없이는 못 가요", "가는 길에 갈 수 있는 충전소가 없어요", canGo = false)
    val why = rec.stop.status.message.ifBlank { reasonText(rec.stop.status.reasonCode) }
    return RouteCardUi("가는 길에 충전이 필요해요", "${rec.alongKm.toInt()}km 지점 ${rec.stop.station.name} · $why", canGo = true)
}

/** 디버그 빌드: 운전자가 내비에 넣는 일반 목적지(번갈아). */
private val DEV_DESTINATIONS = listOf(
    RouteTarget("서울특별시청", GeoPoint(37.5663, 126.9779), null),
    RouteTarget("수원역", GeoPoint(37.2664, 127.0001), null),
)
private var devDestination = 0

/**
 * 디버그 빌드 전용: 글레오가 보내는 것과 같은 형식(액션 = 기능 이름, ACTION_PARAM = JSON)으로 우리 앱에 보낸다.
 * 에뮬레이터에 글레오가 없거나 승인 전이어도 음성 흐름을 시연할 수 있다. 대답은 판단 기록에 남는다.
 */
private fun gleoMocks(context: android.content.Context): List<Pair<String, () -> Unit>> {
    fun send(function: String, params: JSONObject) = context.sendBroadcast(
        Intent(function).setPackage(context.packageName).putExtra("ACTION_PARAM", params.toString()),
    )
    return listOf(
        "글레오: 가는 길 충전소?" to { send("CHECK_CHARGERS", JSONObject().put("scope", "route")) },
        "글레오: 들렀다 가자" to { send("ADD_CHARGER_STOP", JSONObject().put("scope", "route")) },
        "글레오: 3번 결제 안 돼" to { send("REPORT_CHARGER_PROBLEM", JSONObject().put("problem", "PAYMENT").put("charger_number", "3")) },
    )
}
