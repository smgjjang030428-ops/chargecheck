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
 * 이름에서 충전소를 가려내는 낱말. 일반 낱말과, 낱말 끝에 붙은 일반 낱말("전기차충전소")은 뺀다.
 * 숫자는 한 글자여도 남긴다("SW드림타운 3"의 3).
 */
private fun keyWords(name: String): List<String> =
    name.split(Regex("[^\\p{L}\\p{N}]+")).mapNotNull { raw ->
        var w = raw
        while (true) {
            val g = GENERIC_WORDS.firstOrNull { w.length > it.length && w.endsWith(it) } ?: break
            w = w.dropLast(g.length)
        }
        w.takeIf { it !in GENERIC_WORDS && (it.length >= 2 || it.all(Char::isDigit)) }
    }

/** 충전소 이름이 낱말을 모두 담는지. 숫자는 따로 떨어진 숫자로만 맞춘다(3이 13에 맞지 않게). */
private fun Station.hasAll(words: List<String>): Boolean = nameHasAll(name, words)

private fun nameHasAll(name: String, words: List<String>): Boolean {
    val numbers = Regex("\\d+").findAll(name).map { it.value }.toSet()
    return words.all { w -> if (w.all(Char::isDigit)) w in numbers else name.contains(w) }
}

/** 내비 이름으로 우리 DB를 찾을 때 쓸 검색어("SW드림타운 3 전기차충전소" → "SW드림타운 3"). */
fun stationSearchWords(naviName: String): String = keyWords(naviName).joinToString(" ")

/** 우리 충전소 이름이 내비 이름의 낱말·번호를 모두 담는지(일반 낱말만 있으면 false). */
fun nameMatchesNavi(stationName: String, naviName: String): Boolean =
    keyWords(naviName).let { it.isNotEmpty() && nameHasAll(stationName, it) }

/**
 * 내비 목적지·경유지를 우리 충전소와 짝짓는다(기획 §7).
 * 1. POI ID가 같으면 그 충전소.
 * 2. 아니면 [radiusM] 안 충전소. 하나면 그것.
 * 3. 여럿이면(한 건물에 운영사가 여럿 등) 이름이 정확히 같은 곳, 다음은 내비 이름의 낱말·번호를 모두 담은 곳,
 *    다음은 낱말이 겹치는 곳. 그래도 애매하면 null — 엉뚱한 충전소로 경고하지 않는다.
 *    내비는 공공 데이터와 이름을 다르게 붙인다(내비 "SW드림타운 3 전기차충전소" = 공공 "경기성남 SW드림타운 3").
 */
fun matchStation(target: RouteTarget, known: List<Station>, radiusM: Double = 100.0): Station? {
    known.firstOrNull { it.id.value == target.poiId }?.let { return it }
    val words = keyWords(target.name)
    // 좌표가 없는 경유지(에뮬레이터 내비는 경유지를 이름만 주고 좌표는 0,0)는 이름이 같거나 낱말·번호를 모두 담은 곳 하나만
    if (target.location.lat == 0.0 && target.location.lon == 0.0) {
        val unique = known.distinctBy { it.id }
        unique.singleOrNull { it.name.trim() == target.name.trim() }?.let { return it }
        return if (words.isEmpty()) null else unique.filter { it.hasAll(words) }.singleOrNull()
    }
    val near = known.filter { it.location.distanceMetersTo(target.location) <= radiusM }
    if (near.size <= 1) return near.firstOrNull()
    near.singleOrNull { it.name.trim() == target.name.trim() }?.let { return it }
    if (words.isEmpty()) return null
    near.filter { it.hasAll(words) }.singleOrNull()?.let { return it }
    return near.filter { s -> words.any { !it.all(Char::isDigit) && s.name.contains(it) } }.singleOrNull()
}
