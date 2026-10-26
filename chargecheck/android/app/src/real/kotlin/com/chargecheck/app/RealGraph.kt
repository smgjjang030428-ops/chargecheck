package com.chargecheck.app

import android.content.Context
import android.content.pm.ApplicationInfo
import com.chargecheck.adapter.android.CarUxRestrictionsAdapter
import com.chargecheck.adapter.pleos.PleosNaviAdapter
import com.chargecheck.adapter.pleos.PleosVehicleAdapter
import com.chargecheck.adapter.remote.KeyValueStore
import com.chargecheck.adapter.remote.RemoteApiAdapter
import com.chargecheck.core.config.ChargeCheckConfig
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.NearbyStation
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
import com.chargecheck.feature.session.stationId
import com.chargecheck.feature.warning.ArrivalWarningCoordinator
import com.chargecheck.feature.warning.matchStation
import com.chargecheck.feature.warning.shouldShowRouteCard
import com.chargecheck.ui.SearchUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/** 개발 PC에서 돌리는 서버. 에뮬레이터의 10.0.2.2 = PC의 127.0.0.1. 배포 서버가 생기면 release 값만 바꾼다. */
private const val DEV_SERVER = "http://10.0.2.2:8000"

/** 지도 기본 위치(강남역). 차 위치를 모르면 여기를 보여준다. */
internal val DEFAULT_AREA = GeoPoint(37.4979, 127.0276)

/** 주행 중 경로 카드를 보여주는 시간. 지나면 숨기고(내비를 가리지 않게) 주차하면 패널로 다시 보인다. */
private val ROUTE_CARD_DRIVING = Duration.ofSeconds(15)

/**
 * real flavor의 앱 전체 상태. Pleos Vehicle·NaviHelper·CarUxRestrictions 어댑터와 ChargeCheck 서버를
 * SessionCoordinator·ArrivalWarningCoordinator에 연결하고, 내비 목적지를 지켜보며 '가는 길 충전소'를 판단한다.
 *
 * 화면(FlavorContent)과 글레오 수신기(GleoActionReceiver)가 같은 상태를 쓰도록 프로세스에 하나만 둔다.
 */
internal class RealGraph private constructor(context: Context) {

    companion object {
        @Volatile private var instance: RealGraph? = null

        fun get(context: Context): RealGraph = instance ?: synchronized(this) {
            instance ?: RealGraph(context.applicationContext).also {
                instance = it
                it.start()
            }
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock = object : ClockPort {
        override fun now(): Instant = Instant.now()
    }
    val vehicle = PleosVehicleAdapter(context, clock)
    val drivingState = CarUxRestrictionsAdapter(context)
    val navi = PleosNaviAdapter(context)
    private val prefs = context.getSharedPreferences("chargecheck", Context.MODE_PRIVATE)
    val settings = AppSettings(context)
    val api = RemoteApiAdapter(DEV_SERVER, deviceId(), store = object : KeyValueStore {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    })

    /** 서버 연결 상태. 끊기면 화면에 띄우고, 기록은 모아 두었다가 보낸다. */
    val online: StateFlow<Boolean> = api.online

    /** 아직 서버에 못 보낸 충전 기록 수. */
    val pending = MutableStateFlow(api.pendingCount())

    /** 앱 화면이 안 보일 때 짧게 알릴 문장(경유지 추가 등). [CardNotifier]가 알림으로 띄운다. */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val packageName: String = context.packageName

    /** 서버에서 받은 주변 충전소와 판정. 지도·충전소 판별·글레오가 같이 쓴다. */
    val stations = MutableStateFlow<List<NearbyStation>>(emptyList())

    /** 내비 목적지까지 가는 길의 충전소. 충전이 필요할 때만 채워진다(자동). */
    val plan = MutableStateFlow<RoutePlan?>(null)

    /** 주행 중 경로 카드(15초)를 보이는 중인지. */
    val routeCardOnDriving = MutableStateFlow(false)

    /** 디버그 빌드는 에뮬레이터 확인용으로 판정 시간을 줄인다(정차 30초, 급속 정체 2분, 출발 확인 20초). */
    val devTools = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val config = if (devTools) {
        ChargeCheckConfig(
            onsiteMinStop = Duration.ofSeconds(30),
            stallTimeoutFast = Duration.ofMinutes(2),
            stallTimeoutSlow = Duration.ofMinutes(3),
            waitExtension = Duration.ofMinutes(1),
            retroMinElapsed = Duration.ofMinutes(2),
            attemptMinStop = Duration.ofSeconds(20),
        )
    } else {
        ChargeCheckConfig.DEFAULT
    }

    /** 기획 §7 StationResolver: POI ID → 100m 안 하나 → 이름이 겹치는 곳. 애매하면 null. */
    val resolve: (RouteTarget) -> Station? = { target -> matchStation(target, stations.value.map { it.station }) }

    val session = SessionCoordinator(
        machine = ChargeSessionMachine(DeviceId(UUID.randomUUID().toString()), { UUID.randomUUID().toString() }, config),
        vehicle = vehicle,
        navi = navi,
        location = navi,
        api = api,
        clock = clock,
        resolveStation = resolve,
        parked = drivingState.isParked(),
    )
    val warning = ArrivalWarningCoordinator(navi, api, navi, resolve, log = session::note, enabled = { settings.arrivalWarning })
    private var notifier: CardNotifier? = null

    private val time = flow {
        while (true) {
            emit(Instant.now())
            delay(5_000)
        }
    }

    /** 검색에서 고른 지역. 있으면 차 주변 대신 이곳의 충전소를 보여준다. */
    private var focusArea: GeoPoint? = null
    private var lastDestinationKey: String? = null
    private var routeCardJob: Job? = null
    private var drivingCardShownFor: RoutePlan? = null
    private var parkedNow = true

    /** 디버그 빌드 전용: 남은 주행 8km·배터리 8%로 흉내(에뮬레이터에서 Virtual Console 없이 카드 확인). */
    var devLowBattery = false

    /** 경로 카드에 '괜찮아요'를 연속으로 누른 횟수. 2번이면 정말 못 갈 때만 띄운다. */
    private var dismissStreak: Int
        get() = prefs.getInt("route_dismiss_streak", 0)
        set(v) = prefs.edit().putInt("route_dismiss_streak", v).apply()

    private fun start() {
        session.start(scope, time)
        warning.start(scope)
        scope.launch {
            while (true) {
                refreshStations()
                if (api.pendingCount() > 0) {
                    val sent = api.flushOutbox()
                    if (sent > 0) session.note("모아 둔 기록 ${sent}건 전송")
                }
                pending.value = api.pendingCount()
                delay(30_000)
            }
        }
        scope.launch { navi.destination().collect { onDestination(it) } }
        scope.launch {
            drivingState.isParked().collect { parked ->
                val departed = parkedNow && !parked
                parkedNow = parked
                plan.value?.takeIf { departed }?.let { showDrivingCard(it) }
            }
        }
    }

    /** 백그라운드 서비스가 켜질 때 한 번: 앱 화면이 안 보이면 카드를 알림으로 띄운다. */
    fun startNotifier(context: Context) {
        if (notifier != null) return
        notifier = CardNotifier(context.applicationContext, this).also { it.start() }
    }

    /** 차 주변 5km(검색한 지역이 있으면 그곳 3km), 없으면 기본 지역의 충전소와 판정을 서버에서 받아온다. */
    suspend fun refreshStations() {
        val focus = focusArea
        if (focus != null) {
            api.nearbyWithStatus(focus, 3_000).getOrNull()?.let { stations.value = it }
            return
        }
        val car = navi.lastKnown()
        val near = car?.let { api.nearbyWithStatus(it, 5_000).getOrNull() }.orEmpty()
        val list = near.ifEmpty { api.nearbyWithStatus(DEFAULT_AREA, 5_000).getOrNull().orEmpty() }
        if (list.isNotEmpty() || stations.value.isEmpty()) stations.value = list
        recheckDestination()
    }

    /** 충전소로 못 알아본 내비 목적지. 시동 직후엔 차 주변 충전소 목록보다 목적지가 먼저 와서 생긴다. */
    private var unresolvedDestination: RouteTarget? = null

    /** 충전소 목록이 바뀌면, 아까 못 알아본 목적지가 이제 충전소로 보이는지 다시 본다. */
    private suspend fun recheckDestination() {
        val dest = unresolvedDestination ?: return
        val station = resolve(dest) ?: return
        unresolvedDestination = null
        session.note("목적지 다시 확인: ${dest.name} = 충전소")
        closeRoute()
        if (session.state.value is SessionState.Idle) session.dispatch(SessionInput.RouteTargetIsStation(station))
    }

    /** 충전소·지역 찾기(우리 DB). 충전소가 안 나오면 일반 목적지로 보고 [내비에서 찾기]를 권한다. */
    suspend fun search(query: String): SearchUi {
        val q = query.trim()
        if (q.isEmpty()) return SearchUi()
        return api.searchPlaces(q, navi.lastKnown() ?: DEFAULT_AREA).fold(
            onSuccess = { if (it.isEmpty()) SearchUi(q, it, "'$q'와 맞는 충전소가 없어요", naviQuery = q) else SearchUi(q, it) },
            onFailure = { SearchUi(q, emptyList(), "검색 서버에 연결하지 못했어요") },
        )
    }

    /** 검색 결과의 충전소 주변을 지도에 보여준다. */
    suspend fun focus(place: Place) {
        focusArea = place.location
        refreshStations()
    }

    suspend fun clearFocus() {
        if (focusArea == null) return
        focusArea = null
        refreshStations()
    }

    /** 내비 목적지가 바뀔 때마다: 충전소면 도착 전 경고(F3)에 맡기고, 아니면 가는 길에 충전이 필요한지 본다. */
    private suspend fun onDestination(dest: RouteTarget?) {
        // 길안내를 다시 시작할 때(경유지 추가 등) 내비가 잠깐 '목적지 없음'을 보낸다.
        // 그래서 목적지가 비어도 마지막 목적지를 잊지 않는다 — 같은 목적지로 카드를 두 번 띄우지 않기 위해.
        if (dest == null) {
            closeRoute()
            return
        }
        val key = "${dest.name}@${(dest.location.lat * 1e4).roundToInt()},${(dest.location.lon * 1e4).roundToInt()}"
        if (key == lastDestinationKey) return
        lastDestinationKey = key
        unresolvedDestination = dest.takeIf { resolve(it) == null }
        if (resolve(dest) != null || !settings.routeCards) {
            closeRoute()
            return
        }
        val p = planRoute(Place(dest.name, "", dest.location), dest.remainM).getOrElse {
            session.note("가는 길 충전소 조회 실패 — 서버 연결 확인")
            return
        }
        // 이미 충전소를 경유지로 넣어 뒀으면(직접, 내비 자동, 앱 재시작 전) 다시 권하지 않는다
        val known = stations.value.map { it.station } + p.candidates.map { it.stop.station }
        if (navi.routeTargets().first().dropLast(1).any { matchStation(it, known) != null }) {
            session.note("목적지 ${dest.name}: 이미 충전소를 경유지로 넣어 둠 → 카드 생략")
            return
        }
        if (!shouldShowRouteCard(p, dismissStreak)) {
            session.note("목적지 ${dest.name} 약 ${p.routeKm.roundToInt()}km: 충전 없이 갈 수 있어 조용히 있음")
            closeRoute()
            return
        }
        plan.value = p
        if (!parkedNow) showDrivingCard(p)
        val rec = p.candidates.firstOrNull { it.stop.station.id == p.recommendedId }
        session.note("가는 길 충전 필요 → 카드: " + (rec?.let { "${it.alongKm}km 지점 ${it.stop.station.name}" } ?: "갈 수 있는 충전소 없음"))
    }

    /** 목적지까지 가는 길의 충전소. 차의 현재 위치·남은 주행 가능 거리·배터리·내비 남은 거리를 함께 보낸다. */
    suspend fun planRoute(to: Place, remainM: Int? = null): Result<RoutePlan> {
        val from = navi.lastKnown() ?: DEFAULT_AREA
        val range = if (devLowBattery) 8.0 else vehicle.remainingRangeKm()
        val soc = if (devLowBattery) 8 else vehicle.currentSoc()
        session.note("가는 길 충전소 확인: ${to.name} · 남은 주행 ${range?.let { "%.0fkm".format(it) } ?: "모름"} · 배터리 ${soc ?: "모름"}%")
        return api.alongRoute(from, to, range, soc, remainM?.let { it / 1000.0 })
    }

    /** [들렀다 가기]: 목적지는 이미 내비에 있으므로 충전소를 경유지로만 넣는다. */
    suspend fun addStop(station: Station): Boolean {
        val ok = navi.addWaypoint(RouteTarget(station.name, station.location, station.id.value)).isSuccess
        session.note("경유지 추가: ${station.name}" + if (ok) "" else " — 실패")
        if (ok) {
            dismissStreak = 0
            closeRoute()
            notices.tryEmit("경유지에 ${station.name} 추가 · 내비가 새 경로로 안내해요")
        }
        return ok
    }

    /** 알림 카드·주행 카드의 [들렀다 가기]: 추천 충전소를 경유지로. */
    suspend fun addRecommendedStop(): Boolean {
        val p = plan.value ?: return false
        val rec = p.candidates.firstOrNull { it.stop.station.id == p.recommendedId } ?: return false
        return addStop(rec.stop.station)
    }

    /** [괜찮아요]. 두 번 연속이면 다음부터 덜 띄운다. */
    fun dismissRoute() {
        dismissStreak += 1
        session.note("경로 카드 닫음(괜찮아요 ${dismissStreak}번 연속)")
        closeRoute()
    }

    /**
     * 주행 화면 경로 카드: 주차 중 패널에 답하지 않고 출발했거나, 주행 중에 목적지가 바뀌었을 때
     * 15초만, 같은 경로 카드는 한 번만 보인다(끼어들기 예산).
     */
    private fun showDrivingCard(p: RoutePlan) {
        if (drivingCardShownFor === p) return
        drivingCardShownFor = p
        routeCardOnDriving.value = true
        routeCardJob?.cancel()
        routeCardJob = scope.launch {
            delay(ROUTE_CARD_DRIVING.toMillis())
            routeCardOnDriving.value = false
        }
    }

    private fun closeRoute() {
        plan.value = null
        routeCardOnDriving.value = false
        routeCardJob?.cancel()
    }

    /** 지금 차가 있는 충전소: 진행 중인 세션의 충전소, 아니면 차에서 150m 안 가장 가까운 곳. */
    suspend fun stationHere(): Station? {
        val known = stations.value.map { it.station }
        session.state.value.stationId()?.let { id -> known.firstOrNull { it.id == id }?.let { return it } }
        val car = navi.lastKnown() ?: return null
        return known.minByOrNull { it.location.distanceMetersTo(car) }?.takeIf { it.location.distanceMetersTo(car) <= 150 }
    }

    /** 지금 내비 목적지. */
    suspend fun currentDestination(): RouteTarget? = navi.destination().first()

    private fun deviceId(): DeviceId {
        // 설치마다 한 번 만드는 임의 UUID. 서버에는 해시로만 저장된다(기획 §11).
        val id = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
        return DeviceId(id)
    }
}
