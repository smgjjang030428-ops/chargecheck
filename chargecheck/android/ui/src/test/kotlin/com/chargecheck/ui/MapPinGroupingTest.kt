package com.chargecheck.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MapPinGroupingTest {
    @Test fun `nearby pins group and separate again after zoom`() {
        val near = listOf(MapPinPoint("a", 0.0, 0.0), MapPinPoint("b", 20.0, 0.0))
        assertEquals(1, groupMapPins(near, 48.0).size)
        assertEquals(2, groupMapPins(near.map { it.copy(x = it.x * 4) }, 48.0).size)
    }

    @Test fun `selected and recommended pins remain individually selectable`() {
        val points = listOf(MapPinPoint("a", 0.0, 0.0, true), MapPinPoint("b", 0.0, 0.0), MapPinPoint("c", 1.0, 1.0))
        assertEquals(listOf(listOf("a"), listOf("b", "c")), groupMapPins(points, 48.0).map { g -> g.map { it.id } })
    }

    @Test fun `all pins preserved with stable membership regardless of input order`() {
        val points = listOf(MapPinPoint("a", 0.0, 0.0), MapPinPoint("b", 10.0, 0.0), MapPinPoint("c", 300.0, 0.0))
        val groups = groupMapPins(points, 48.0)
        assertEquals(points.map { it.id }.sorted(), groups.flatten().map { it.id }.sorted())
        assertEquals(groups, groupMapPins(points.reversed(), 48.0))
        assertEquals(emptyList(), groupMapPins(emptyList(), 48.0))
    }
}
