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
 * Pleos NaviHelper로 기본 내비의 목적지·경유지·도착·현재 위치를 받고, 목적지·경유지를 넘긴다. (§4.14)
 * 위치도 내비에서 받으므로 LocationPort를 함께 구현한다(FusedLocation SDK는 아직 Coming Soon).
 */
class PleosNaviAdapter(context: Context) : NaviPort, LocationPort {

    private val navi = NaviHelper(context)
    private var destination: RouteTarget? = null
    private var waypoints: List<RouteTarget> = emptyList()

    private val targets = MutableStateFlow<List<RouteTarget>>(emptyList())
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
        override fun onRouteCancelled() = refresh("길안내 취소")

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
    override fun arrivals(): Flow<RouteTarget> = arrived

    override suspend fun addWaypoint(target: RouteTarget): Result<Unit> = runCatching {
        navi.addWaypoint(
            RequestWaypointInfo(target.location.lat, target.location.lon, target.name, WaypointIndex.FIRST, target.poiId ?: "", "", "", ""),
        )
    }

    override suspend fun setDestination(target: RouteTarget): Result<Unit> = runCatching {
        navi.requestRoute(
            RouteInfo(target.location.lat, target.location.lon, null, null, target.name, target.poiId ?: "", "", "", RouteOption.RECOMMENDED, "", false),
        )
    }

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
