package com.chargecheck.feature.warning

import com.chargecheck.core.model.RoutePlan
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.distanceMetersTo
import com.chargecheck.core.port.RouteTarget

/**
 * 내비 목적지가 바뀌었을 때 '가는 길 충전소' 카드를 띄울지.
 * - 충전이 필요할 때만 띄운다. 충전 없이 갈 수 있으면 조용히 있는다.
 * - 운전자가 '괜찮아요'를 두 번 연속 누르면, 그다음부터는 남은 거리로 정말 못 갈 때(여유 없이도 부족)만 띄운다.
 */
fun shouldShowRouteCard(plan: RoutePlan, dismissStreak: Int): Boolean {
    if (!plan.needsCharge) return false
    if (dismissStreak < 2) return true
    val range = plan.rangeKm ?: return true
    return plan.routeKm > range
}

/** 어느 충전소 이름에나 들어가는 낱말. 이름 비교에서 뺀다. */
private val GENERIC_WORDS = setOf("충전소", "충전기", "주차장", "급속", "완속", "전기차", "공영", "EV")

/**
 * 내비 목적지·경유지를 우리 충전소와 짝짓는다(§4.8).
 * 1. POI ID가 같으면 그 충전소.
 * 2. 아니면 [radiusM] 안 충전소. 하나면 그것.
 * 3. 여럿이면(한 건물에 운영사가 여럿 등) 이름이 겹치는 곳만. 그래도 애매하면 null — 엉뚱한 충전소로 경고하지 않는다.
 */
fun matchStation(target: RouteTarget, known: List<Station>, radiusM: Double = 100.0): Station? {
    known.firstOrNull { it.id.value == target.poiId }?.let { return it }
    val near = known.filter { it.location.distanceMetersTo(target.location) <= radiusM }
    if (near.size <= 1) return near.firstOrNull()
    val words = target.name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in GENERIC_WORDS }
    val named = near.filter { s -> words.any { s.name.contains(it) } }
    return named.singleOrNull()
}
