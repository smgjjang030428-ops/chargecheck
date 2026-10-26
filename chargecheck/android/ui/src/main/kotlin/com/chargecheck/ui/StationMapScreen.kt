package com.chargecheck.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargerStatus
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.PublicState
import com.chargecheck.core.model.StationInfo
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.Place
import com.chargecheck.core.model.RoutePlan
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.VoiceIntent
import com.chargecheck.feature.session.ChargerPick
import com.chargecheck.feature.session.Effect
import com.chargecheck.feature.session.FailConfirm
import com.chargecheck.feature.session.LogEntry
import com.chargecheck.feature.session.SessionState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint as OsmPoint

/** 지도에 찍을 충전소 하나. 색은 우리 판정(verdict). */
data class MapStation(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val verdict: Verdict,
    val reason: String,
    val info: StationInfo = StationInfo(),
    val chargers: List<ChargerStatus> = emptyList(),
    /** 경로 추천 충전소: 핀을 크게 그린다. */
    val highlight: Boolean = false,
)

/**
 * real flavor 화면. 주차 중에는 왼쪽에 주변 충전소 지도(우리 판정 색 핀), 오른쪽에 세션 상태.
 * 핀을 누르면 상세 카드 → [여기로 안내]로 기본 내비에 목적지를 넘긴다.
 * 지도 위 검색 상자로 목적지를 고르면 [plan](가는 길의 충전소)을 받아 경로선과 오른쪽 [RoutePanel]을 보여준다.
 * 이때 [stations]에는 경로 위 충전소가 들어오고, 상세 카드의 버튼은 [들렀다 가기](경유지 안내)가 된다.
 * 주행 중에는 [DrivingScreen]으로 바꿔 경고·상태 한 가지만 크게 보여준다(Pleos 주행 안전 가이드).
 */
@Composable
fun StationMapScreen(
    stations: List<MapStation>,
    car: GeoPoint?,
    selectedId: String?,
    parked: Boolean,
    state: SessionState,
    question: Effect.Ask?,
    warning: WarningUi?,
    toast: String?,
    log: List<LogEntry>,
    uploads: List<ChargeEvent>,
    devTools: Boolean,
    onSelect: (String?) -> Unit,
    onNavigate: (String) -> Unit,
    onAnswer: (VoiceIntent) -> Unit,
    onWarningChoice: (VoiceIntent) -> Unit,
    onDevArrive: () -> Unit,
    chargerPick: ChargerPick? = null,
    onPickCharger: (String?) -> Unit = {},
    search: SearchUi = SearchUi(),
    onQueryChange: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onPickPlace: (Place) -> Unit = {},
    plan: RoutePlan? = null,
    onRouteGo: (String?) -> Unit = {},
    onRouteClose: () -> Unit = {},
    confirm: FailConfirm? = null,
    onConfirm: (Boolean) -> Unit = {},
    canReport: (String) -> Boolean = { false },
    onReportFail: (String) -> Unit = {},
    routeCard: RouteCardUi? = null,
    onRouteCard: (Boolean) -> Unit = {},
    onNaviSearch: (String) -> Unit = {},
    /** 디버그 빌드 전용 버튼(글레오 흉내 등). */
    devExtra: List<Pair<String, () -> Unit>> = emptyList(),
    /** 서버 연결이 끊겼을 때 등 한 줄 안내. */
    banner: String? = null,
    onSettings: (() -> Unit)? = null,
) {
    if (!parked) {
        DrivingScreen(state, warning, onWarningChoice, confirm, onConfirm, routeCard, onRouteCard)
        return
    }
    Box(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1.5f).fillMaxHeight().clip(RoundedCornerShape(20.dp))) {
                StationMap(stations, car, onSelect, plan)
                MapLegend(Modifier.align(Alignment.BottomStart).padding(16.dp))
                stations.firstOrNull { it.id == selectedId }?.let { s ->
                    val label = if (plan != null) "들렀다 가기" else "여기로 안내"
                    val report = if (plan == null && canReport(s.id)) onReportFail else null
                    StationCard(Modifier.align(Alignment.BottomCenter).padding(16.dp), s, label, onNavigate, { onSelect(null) }, report)
                }
                SearchBox(Modifier.align(Alignment.TopStart).padding(16.dp), search, onQueryChange, onSearch, onPickPlace, onNaviSearch)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (banner != null || onSettings != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            banner ?: "",
                            color = TextMain,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f).then(
                                if (banner != null) Modifier.clip(RoundedCornerShape(12.dp)).background(Yellow.copy(alpha = 0.25f)).padding(12.dp) else Modifier,
                            ),
                        )
                        onSettings?.let { OutlinedButton(onClick = it, shape = RoundedCornerShape(12.dp)) { Text("설정", color = TextMain, fontSize = 20.sp) } }
                    }
                }
                if (plan != null) {
                    RoutePanel(Modifier.weight(2f).fillMaxWidth(), plan, { onSelect(it) }, onRouteGo, onRouteClose)
                    SessionPanel(Modifier.weight(1f).fillMaxWidth(), state, engineOn = true, toast = toast, uploads = uploads)
                } else {
                    SessionPanel(Modifier.weight(1f).fillMaxWidth(), state, engineOn = true, toast = toast, uploads = uploads)
                    LogPanel(Modifier.weight(1f).fillMaxWidth(), log)
                }
                if (devTools) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("도착 처리" to onDevArrive) + devExtra).forEach { (label, action) ->
                            OutlinedButton(onClick = action, modifier = Modifier.weight(1f)) {
                                Text(label, color = TextSub, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        if (warning != null) WarningCard(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), warning, onWarningChoice)
        if (question != null) {
            AskSheet(Modifier.align(Alignment.BottomCenter), question, onAnswer)
        } else if (confirm != null && confirm.visible) {
            // 출발할 때 못 받은 "충전이 안 됐나요?"를 주차하면 다시 묻는다
            AskSheet(Modifier.align(Alignment.BottomCenter), Effect.Ask(confirm.question, retro = true)) { onConfirm(it == VoiceIntent.YES) }
        } else if (chargerPick != null) {
            ChargerPickSheet(Modifier.align(Alignment.BottomCenter), chargerPick, onPickCharger)
        }
    }
}

@Composable
private fun StationMap(stations: List<MapStation>, car: GeoPoint?, onSelect: (String?) -> Unit, plan: RoutePlan?) {
    val context = LocalContext.current
    val map = remember {
        // OSM 타일 서버 이용 정책: 앱을 구분할 수 있는 User-Agent 필수. 출시 전에는 상용 지도 타일로 교체한다.
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }
    DisposableEffect(Unit) { onDispose { map.onDetach() } }
    AndroidView(
        factory = { map },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            view.overlays.clear()
            plan?.let { p ->
                view.overlays += Polyline(view).apply {
                    setPoints(p.path.map { OsmPoint(it.lat, it.lon) })
                    outlinePaint.color = android.graphics.Color.rgb(40, 110, 230)
                    outlinePaint.strokeWidth = 12f
                }
                view.overlays += Marker(view).apply {
                    position = OsmPoint(p.destination.location.lat, p.destination.location.lon)
                    icon = dot(view, android.graphics.Color.WHITE, MARKER_DP)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = p.destination.name
                }
            }
            stations.forEach { s ->
                view.overlays += Marker(view).apply {
                    position = OsmPoint(s.location.lat, s.location.lon)
                    icon = dot(view, s.verdict.color().toArgb(), if (s.highlight) 48 else MARKER_DP)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = s.name
                    setOnMarkerClickListener { _, _ -> onSelect(s.id); true }
                }
            }
            car?.let {
                view.overlays += Marker(view).apply {
                    position = OsmPoint(it.lat, it.lon)
                    icon = dot(view, android.graphics.Color.rgb(40, 110, 230), MARKER_DP)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "내 차"
                }
            }
            if (plan != null) {
                // 경로가 새로 오면 출발~목적지가 다 보이게 한 번 맞춘다
                if (view.tag !== plan && plan.path.size >= 2) {
                    val pts = (plan.path + listOfNotNull(car)).map { OsmPoint(it.lat, it.lon) }
                    view.post { view.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.15f), false, 60) }
                    view.tag = plan
                }
                view.invalidate()
                return@AndroidView
            }
            // 차가 충전소들 근처(3km)면 차를, 멀리 있으면 충전소들의 가운데를 보여준다
            val middle = stations.takeIf { it.isNotEmpty() }
                ?.let { s -> GeoPoint(s.map { it.location.lat }.average(), s.map { it.location.lon }.average()) }
            val center = if (car != null && (middle == null || car.distanceMetersTo(middle) < 3_000)) car else middle
            // 처음 한 번, 그리고 보여줄 지역이 2km 넘게 바뀌었을 때만 옮긴다(운전자가 지도를 움직인 건 존중).
            // 앱이 켜질 때 차 위치가 아직 없어 기본 지역을 보여줬다가, 위치가 오면 차 주변으로 옮겨 간다.
            if (view.tag is RoutePlan) view.controller.setZoom(16.0) // 경로 보기를 닫으면 원래 확대로
            val last = view.tag as? GeoPoint
            if (center != null && (last == null || last.distanceMetersTo(center) > 2_000)) {
                view.controller.setCenter(OsmPoint(center.lat, center.lon))
                view.tag = center
            }
            view.invalidate()
        },
    )
}

/** 지도 표시는 36dp 이상(안드로이드 차량용 가이드, 기획 §9). */
private const val MARKER_DP = 36

private fun dot(view: android.view.View, color: Int, sizeDp: Int) = GradientDrawable().apply {
    val density = view.resources.displayMetrics.density
    val px = (sizeDp * density).toInt()
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
    setSize(px, px)
}

@Composable
private fun MapLegend(modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(Verdict.GREEN to "최근 정상", Verdict.YELLOW to "주의", Verdict.RED to "실패 많음", Verdict.GRAY to "기록 없음").forEach { (v, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(v.color()))
                Spacer(Modifier.width(6.dp))
                Text(label, color = TextMain, fontSize = 16.sp)
            }
        }
    }
}

/**
 * 충전소 상세 카드(주차 중에만): 판정 + 충전 타입 + 커넥터 + 충전기별 실시간 상태 + 결제 + 이용 정보.
 * 결제 수단은 공공 데이터에 없으므로 지어내지 않고 운영사 연락처로 안내한다.
 */
@Composable
private fun StationCard(
    modifier: Modifier,
    s: MapStation,
    navigateLabel: String,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
    onReportFail: ((String) -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).background(Panel).padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(18.dp).height(18.dp).clip(RoundedCornerShape(9.dp)).background(s.verdict.color()))
            Spacer(Modifier.width(12.dp))
            Text(s.name, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(s.reason, color = TextSub, fontSize = 20.sp)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.chargers.isNotEmpty()) {
                InfoRow("충전 타입", chargeTypeText(s.chargers))
                InfoRow("커넥터", s.chargers.flatMap { it.connectors }.distinct().joinToString(" · ").ifBlank { "정보 없음" })
                InfoRow("실시간", availabilityText(s.chargers))
                s.chargers.forEach { ChargerLine(it) }
            }
            InfoRow("결제", s.info.payment ?: paymentFallback(s.info))
            val usage = listOfNotNull(
                s.info.useTime.ifBlank { null },
                when (s.info.parkingFree) { true -> "주차 무료"; false -> "주차 유료"; null -> null },
                s.info.limitDetail.ifBlank { null },
            )
            if (usage.isNotEmpty()) InfoRow("이용", usage.joinToString(" · "))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { onNavigate(s.id) },
                colors = ButtonDefaults.buttonColors(containerColor = TextMain, contentColor = Bg),
                shape = RoundedCornerShape(14.dp),
            ) { Text(navigateLabel, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(14.dp)) {
                Text("닫기", color = TextMain, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            }
            // 현장(지금 세션의 충전소이거나 150m 안)에서만 보인다. 허위 기록을 막기 위해서다.
            if (onReportFail != null) {
                OutlinedButton(onClick = { onReportFail(s.id) }, shape = RoundedCornerShape(14.dp)) {
                    Text("여기 충전 안 됐어요", color = Red, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, color = TextSub, fontSize = 19.sp, modifier = Modifier.width(96.dp))
        Text(value, color = TextMain, fontSize = 19.sp)
    }
}

/** 충전기 한 대: "01번 · 급속 100kW · DC콤보 · 사용 가능 · 최근 실패 2건" */
@Composable
private fun ChargerLine(c: ChargerStatus) {
    val failed = c.verdict == Verdict.RED || c.verdict == Verdict.YELLOW
    Row(Modifier.padding(start = 96.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.verdict.color()))
        Spacer(Modifier.width(8.dp))
        val kind = if (c.chargerType == ChargerType.SLOW) "완속" else "급속"
        val parts = listOfNotNull(
            "${c.chargerId}번",
            kind + (c.outputKw?.let { " ${it}kW" } ?: ""),
            c.connectors.joinToString("+").ifBlank { null },
            c.publicState.korean(),
        )
        Text(parts.joinToString(" · "), color = TextMain, fontSize = 17.sp)
        if (failed) {
            Spacer(Modifier.width(8.dp))
            Text("최근 실패 ${c.recentFailCount}건", color = c.verdict.color(), fontSize = 17.sp)
        }
    }
}

/** "급속 100kW 4대 · 완속 7kW 2대" */
private fun chargeTypeText(chargers: List<ChargerStatus>): String =
    chargers.groupBy { it.chargerType == ChargerType.SLOW }.entries.sortedBy { it.key }.joinToString(" · ") { (slow, list) ->
        val kw = list.mapNotNull { it.outputKw }.distinct().sorted()
        val kwText = when {
            kw.isEmpty() -> ""
            kw.size == 1 -> " ${kw[0]}kW"
            else -> " ${kw.first()}~${kw.last()}kW"
        }
        "${if (slow) "완속" else "급속"}$kwText ${list.size}대"
    }

/** "사용 가능 3 · 충전 중 1 · 중지 1" (0인 항목은 뺌) */
private fun availabilityText(chargers: List<ChargerStatus>): String =
    PublicState.entries.mapNotNull { st -> chargers.count { it.publicState == st }.takeIf { it > 0 }?.let { "${st.korean()} $it" } }
        .joinToString(" · ")

private fun PublicState.korean() = when (this) {
    PublicState.AVAILABLE -> "사용 가능"
    PublicState.CHARGING -> "충전 중"
    PublicState.OUT_OF_SERVICE -> "점검·중지"
    PublicState.UNKNOWN -> "상태 모름"
}

private fun paymentFallback(info: StationInfo): String {
    val who = listOf(info.operator, info.operatorCall).filter { it.isNotBlank() }.joinToString(" ")
    return if (who.isBlank()) "공공 데이터에 결제 정보 없음" else "공공 데이터에 결제 정보 없음 · 운영사 확인: $who"
}

internal fun Verdict.color() = when (this) {
    Verdict.GREEN -> Green
    Verdict.YELLOW -> Yellow
    Verdict.RED -> Red
    Verdict.GRAY -> Gray
}
