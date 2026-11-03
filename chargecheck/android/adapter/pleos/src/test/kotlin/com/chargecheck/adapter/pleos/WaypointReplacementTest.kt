package com.chargecheck.adapter.pleos

import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.port.RouteTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WaypointReplacementTest {
    private val cafe = RouteTarget("카페", GeoPoint(37.51, 127.01), "cafe")
    private val broken = RouteTarget("실패 충전소", GeoPoint(37.50, 127.02), "broken")
    private val alternative = RouteTarget("대체 충전소", GeoPoint(37.49, 127.03), "alternative")

    @Test
    fun `두 번째 충전소만 교체하고 다른 경유지와 순서를 보존한다`() {
        assertEquals(listOf(cafe, alternative), replacementWaypoints(listOf(cafe, broken), 1, alternative))
    }

    @Test
    fun `다른 경유지의 위치를 모르면 삭제하지 않고 변경을 거절한다`() {
        val original = listOf(cafe.copy(location = GeoPoint(0.0, 0.0)), broken)
        assertFailsWith<IllegalArgumentException> { replacementWaypoints(original, 1, alternative) }
        assertEquals(listOf("cafe", "broken"), original.map { it.poiId })
    }

    @Test
    fun `교체 대상만 좌표가 없으면 새 충전소 좌표로 교체할 수 있다`() {
        assertEquals(listOf(cafe, alternative), replacementWaypoints(listOf(cafe, broken.copy(location = GeoPoint(0.0, 0.0))), 1, alternative))
    }

    @Test
    fun `오래된 인덱스나 지원 수 초과는 다른 경유지를 버리지 않고 거절한다`() {
        assertFailsWith<IllegalArgumentException> { replacementWaypoints(listOf(cafe, broken), 2, alternative) }
        assertFailsWith<IllegalArgumentException> { replacementWaypoints(listOf(cafe, broken, cafe), 1, alternative) }
    }

    @Test
    fun `대체 충전소 좌표도 유효해야 한다`() {
        assertFailsWith<IllegalArgumentException> { replacementWaypoints(listOf(broken), 0, alternative.copy(location = GeoPoint(Double.NaN, 127.0))) }
    }
}
