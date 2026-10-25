package com.chargecheck.feature.warning

import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.Place
import com.chargecheck.core.model.RoutePlan
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.port.RouteTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteAdviceTest {

    private fun plan(routeKm: Double, rangeKm: Double?, needsCharge: Boolean) = RoutePlan(
        destination = Place("서울특별시청", "", GeoPoint(37.5663, 126.9779)),
        routeKm = routeKm, roadRoute = false, rangeKm = rangeKm, soc = 20, needsCharge = needsCharge,
        path = emptyList(), candidates = emptyList(), recommendedId = null,
    )

    @Test
    fun `충전 없이 갈 수 있으면 카드를 띄우지 않는다`() {
        assertFalse(shouldShowRouteCard(plan(10.0, 300.0, needsCharge = false), dismissStreak = 0))
    }

    @Test
    fun `충전이 필요하면 띄운다`() {
        assertTrue(shouldShowRouteCard(plan(30.0, 30.0, needsCharge = true), dismissStreak = 0))
    }

    @Test
    fun `괜찮아요를 두 번 누른 뒤에는 정말 못 갈 때만 띄운다`() {
        assertFalse(shouldShowRouteCard(plan(30.0, 32.0, needsCharge = true), dismissStreak = 2)) // 여유는 부족하지만 갈 수는 있음
        assertTrue(shouldShowRouteCard(plan(40.0, 32.0, needsCharge = true), dismissStreak = 2))
    }

    private val here = GeoPoint(37.4012, 127.1086)
    private fun station(id: String, name: String, dLat: Double = 0.0) =
        Station(StationId(id), name, GeoPoint(here.lat + dLat, here.lon), ChargerType.FAST)

    @Test
    fun `POI ID가 같으면 그 충전소`() {
        val a = station("A", "판교 A")
        assertEquals(a, matchStation(RouteTarget("아무 이름", GeoPoint(0.0, 0.0), "A"), listOf(a)))
    }

    @Test
    fun `100m 안에 하나뿐이면 그 충전소`() {
        val a = station("A", "판교역 공영주차장")
        assertEquals(a, matchStation(RouteTarget("판교역", here, "kakao-123"), listOf(a, station("B", "먼 곳", dLat = 0.01))))
    }

    @Test
    fun `100m 안에 여럿이면 이름이 겹치는 곳만, 애매하면 고르지 않는다`() {
        val dream = station("A", "경기성남 SW드림타운 3")
        val other = station("B", "판교 테크원 충전소", dLat = 0.0003)
        assertEquals(dream, matchStation(RouteTarget("SW드림타운 충전소", here, "x"), listOf(dream, other)))
        assertNull(matchStation(RouteTarget("주차장 충전기", here, "x"), listOf(dream, other)))
    }
}
