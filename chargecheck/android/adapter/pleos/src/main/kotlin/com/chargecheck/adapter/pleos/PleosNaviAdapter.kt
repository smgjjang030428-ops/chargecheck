package com.chargecheck.adapter.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.port.LocationPort
import com.chargecheck.core.port.NaviPort
import com.chargecheck.core.port.RouteTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

private const val TAG = "ChargeCheck/Pleos"
private const val POLL_MS = 10_000L

/**
 * Pleos NaviHelper로 기본 내비의 목적지·경유지·도착·현재 위치를 받고, 목적지·경유지를 넘긴다. (기획 §3)
 * 위치도 내비에서 받으므로 LocationPort를 함께 구현한다(FusedLocation SDK는 아직 Coming Soon).
 */
class PleosNaviAdapter(context: Context) : NaviPort, LocationPort {

    private val navi = NaviHelper(context)
    private var destination: RouteTarget? = null
    private var waypoints: List<RouteTarget> = emptyList()

    private val targets = MutableStateFlow<List<RouteTarget>>(emptyList())
    private val cancellations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val arrived = MutableSharedFlow<RouteTarget>(extraBufferCapacity = 8)
    private val location = MutableStateFlow<GeoPoint?>(null)

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationChanged() {
            Log.d(TAG, "목적지 변경 → 목적지·경유지 조회")
            navi.getDestinationInfo()
            navi.getWaypointInfo()
        }

        override fun onWaypointChanged(info: ai.pleos.playground.navi.data.WaypointChangedInfo) {
            navi.getWaypointInfo()
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            Log.d(TAG, "목적지: ${info.name} (${info.latitude}, ${info.longitude}) poi=${info.poiId}")
            // 길안내가 없으면 이름이 비고 좌표가 (0, 0)으로 온다
            destination = if (info.latitude == 0.0 && info.longitude == 0.0) null
            else RouteTarget(info.name, GeoPoint(info.latitude, info.longitude), info.poiId, info.remainDistance.takeIf { it > 0 })
            publish()
        }

        override fun onWayPointInfo(list: List<WaypointInfo>) {
            waypoints = list.map { RouteTarget(it.name, GeoPoint(it.latitude, it.longitude), it.poiId) }
            publish()
        }

        // 에뮬레이터에서는 안내 시작 때 onDestinationChanged 대신 이 이벤트들이 온다(2026-10-24 확인).
        // onRouteEnded는 경로 탐색 화면이 닫힐 때도 오므로, 목적지를 지우지 않고 다시 조회해서 판단한다.
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) = refresh("안내 시작")
        override fun onRoutingCompleted() = refresh("경로 계산 완료")
        override fun onReRouteEnded() = refresh("재탐색 완료")
        override fun onRouteEnded() = refresh("onRouteEnded")
        override fun onRouteCancelled() {
            Log.d(TAG, "길안내 취소 → 이전 경로 정리")
            destination = null
            waypoints = emptyList()
            publish()
            cancellations.tryEmit(Unit)
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            Log.d(TAG, "목적지 도착: ${info.name}")
            destination?.let { arrived.tryEmit(it) }
        }

        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            Log.d(TAG, "경유지 도착: ${info.name}")
            (waypoints.firstOrNull { it.name == info.name } ?: waypoints.firstOrNull())?.let { arrived.tryEmit(it) }
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            if (info.latitude == 0.0 && info.longitude == 0.0) return
            if (location.value == null) Log.d(TAG, "현재 위치: ${info.latitude}, ${info.longitude} ${info.address}")
            location.value = GeoPoint(info.latitude, info.longitude)
        }

        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "NaviHelper 오류: $code")
        }
    }

    /** 이벤트를 놓쳐도 따라잡도록 목적지·경유지·위치를 주기적으로 다시 조회한다. */
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            query()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        navi.initialize(
            {
                Log.d(TAG, "NaviHelper 초기화 완료")
                navi.addListener(listener)
                handler.post(poll)
            },
            { Log.w(TAG, "NaviHelper 초기화 실패", it) },
        )
    }

    private fun query() {
        navi.getDestinationInfo()
        navi.getWaypointInfo()
        navi.getCurrentLocationInfo()
    }

    private fun refresh(why: String) {
        Log.d(TAG, "$why → 목적지 다시 조회")
        query()
    }

    override fun routeTargets(): Flow<List<RouteTarget>> = targets
    override fun routeCancellations(): Flow<Unit> = cancellations
    override fun arrivals(): Flow<RouteTarget> = arrived

    override suspend fun addWaypoint(target: RouteTarget): Result<Unit> = runCatching {
        navi.addWaypoint(waypointInfo(target, 0))
    }

    /**
     * 경유지 교체. removeWaypoint 뒤 바로 addWaypoint를 보내면 내비가 두 요청을 동시에 처리하다 경로를 다시 짜면서
     * 더한 경유지를 버린다(에뮬레이터 로그 2026-10-26: 교체 뒤 wayPoints=[]). 그래서 목적지와 바뀐 경유지 목록을
     * requestRouteWithWaypoints 한 번으로 보낸다. 다른 경유지의 좌표를 모르면 삭제하지 않고 변경을 거절한다.
     */
    override suspend fun replaceWaypoint(index: Int, target: RouteTarget): Result<Unit> = runCatching {
        val dest = checkNotNull(destination) { "목적지가 없음" }
        val list = replacementWaypoints(waypoints, index, target)
        Log.d(TAG, "경유지 교체 요청: ${list.joinToString { it.name }} → ${dest.name}")
        navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(routeInfo(dest), list.mapIndexed { i, w -> waypointInfo(w, i) }))
    }

    /** 목적지 교체. 경유지가 있으면 목적지와 경유지 목록을 requestRouteWithWaypoints 한 번으로 보낸다(경유지 교체와 같은 이유). */
    override suspend fun replaceDestination(target: RouteTarget, waypoints: List<RouteTarget>): Result<Unit> {
        if (waypoints.isEmpty()) return setDestination(target)
        return runCatching {
            require(waypoints.size <= 2) { "경유지는 최대 2개" }
            require(waypoints.none { it.location.lat == 0.0 && it.location.lon == 0.0 }) { "좌표 없는 경유지" }
            Log.d(TAG, "목적지 교체 요청: ${waypoints.joinToString { it.name }} → ${target.name}")
            navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(routeInfo(target), waypoints.mapIndexed { i, w -> waypointInfo(w, i) }))
        }
    }

    override suspend fun setDestination(target: RouteTarget): Result<Unit> = runCatching {
        navi.requestRoute(routeInfo(target))
    }

    private fun routeInfo(t: RouteTarget) =
        RouteInfo(t.location.lat, t.location.lon, null, null, t.name, t.poiId ?: "", "", "", RouteOption.RECOMMENDED, "", false)

    /** [i]번째(0부터) 경유지 요청. 내비 경유지는 최대 2개. */
    private fun waypointInfo(t: RouteTarget, i: Int) =
        RequestWaypointInfo(t.location.lat, t.location.lon, t.name, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, t.poiId ?: "", "", "", "")

    override fun destination(): Flow<RouteTarget?> = targets.map { destination }.distinctUntilChanged()

    override suspend fun openSearch(keyword: String): Result<Unit> = runCatching {
        navi.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
    }

    override fun locations(): Flow<GeoPoint> = location.filterNotNull()

    override suspend fun lastKnown(): GeoPoint? {
        navi.getCurrentLocationInfo()
        return location.value
    }

    /** 경유지를 먼저 들르므로 목적지보다 앞에 둔다. */
    private fun publish() {
        targets.value = waypoints + listOfNotNull(destination)
    }

    fun release() {
        handler.removeCallbacks(poll)
        navi.removeListener(listener)
        navi.release()
    }
}
