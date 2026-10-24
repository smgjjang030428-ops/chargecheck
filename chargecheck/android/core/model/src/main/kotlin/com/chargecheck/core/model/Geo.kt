package com.chargecheck.core.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_000.0

/** 두 좌표 사이의 거리(m). 하버사인 공식. */
fun GeoPoint.distanceMetersTo(other: GeoPoint): Double {
    val dLat = Math.toRadians(other.lat - lat)
    val dLon = Math.toRadians(other.lon - lon)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}
