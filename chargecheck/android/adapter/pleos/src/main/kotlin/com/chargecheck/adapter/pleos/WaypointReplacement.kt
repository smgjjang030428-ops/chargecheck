package com.chargecheck.adapter.pleos

import com.chargecheck.core.port.RouteTarget

/** 내비 요청 전 검증. 다른 경유지를 삭제하거나 순서를 바꿔 유효한 경로처럼 만들지 않는다. */
internal fun replacementWaypoints(current: List<RouteTarget>, index: Int, target: RouteTarget): List<RouteTarget> {
    require(current.size <= 2) { "경유지는 최대 2개" }
    require(index in current.indices) { "변경할 경유지가 현재 경로에 없음" }
    val updated = current.toMutableList().apply { this[index] = target }
    require(updated.all { waypoint ->
        val p = waypoint.location
        p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0 &&
            (p.lat != 0.0 || p.lon != 0.0)
    }) { "좌표를 확인하지 못한 경유지가 있어 경로를 바꾸지 않았어요" }
    return updated
}
