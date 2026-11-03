package com.chargecheck.ui

/** Screen coordinates are recomputed on zoom; highlighted pins stay individually selectable. */
internal data class MapPinPoint(val id: String, val x: Double, val y: Double, val emphasized: Boolean = false)

internal fun groupMapPins(points: List<MapPinPoint>, radius: Double): List<List<MapPinPoint>> {
    require(radius > 0)
    val remaining = points.filterNot { it.emphasized }.sortedBy { it.id }.toMutableList()
    val groups = points.filter { it.emphasized }.map { listOf(it) }.toMutableList()
    while (remaining.isNotEmpty()) {
        val seed = remaining.removeAt(0)
        val neighbors = remaining.filter {
            val dx = it.x - seed.x
            val dy = it.y - seed.y
            dx * dx + dy * dy <= radius * radius
        }
        remaining.removeAll(neighbors.toSet())
        groups += listOf(seed) + neighbors
    }
    return groups
}
