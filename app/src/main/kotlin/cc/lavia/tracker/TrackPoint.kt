package cc.lavia.tracker

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Un punto registrato. [segment] cambia a ogni pausa/ripresa (diventa un <trkseg> nel GPX). */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val timeMs: Long,
    val segment: Int,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distanza in metri (formula di haversine). */
    fun distanceM(a: TrackPoint, b: TrackPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }
}
