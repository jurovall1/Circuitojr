package com.rutas.circuito

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.math.*

object RouteOptimizer {
    private const val ROAD_FACTOR = 1.3   // la línea recta subestima la distancia por calle
    private const val AVG_KMH = 30.0
    private const val STOP_MIN = 3

    /** Distancia en línea recta (km, haversine). */
    fun km(a: Stop, b: Stop): Double {
        val la1 = a.lat ?: return 0.0
        val lo1 = a.lng ?: return 0.0
        val la2 = b.lat ?: return 0.0
        val lo2 = b.lng ?: return 0.0
        val dLat = Math.toRadians(la2 - la1)
        val dLng = Math.toRadians(lo2 - lo1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLng / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(h))
    }

    fun legKm(a: Stop, b: Stop) = km(a, b) * ROAD_FACTOR

    /** Mantiene la primera parada como inicio. Vecino más cercano + 2-opt. */
    fun optimize(stops: List<Stop>, roundTrip: Boolean): List<Stop> {
        val located = stops.filter { it.lat != null && it.lng != null }
        val rest = stops.filter { it.lat == null || it.lng == null }
        if (located.size < 3) return stops
        val order = twoOpt(located, nearest(located), roundTrip)
        return order.map { located[it] } + rest
    }

    private fun nearest(p: List<Stop>): List<Int> {
        val n = p.size
        val visited = BooleanArray(n)
        val order = mutableListOf(0)
        visited[0] = true
        while (order.size < n) {
            val last = order.last()
            var best = -1
            var bd = Double.MAX_VALUE
            for (j in 0 until n) if (!visited[j]) {
                val d = km(p[last], p[j])
                if (d < bd) { bd = d; best = j }
            }
            visited[best] = true
            order.add(best)
        }
        return order
    }

    private fun twoOpt(p: List<Stop>, start: List<Int>, closed: Boolean): List<Int> {
        val o = start.toMutableList()
        val n = o.size
        fun d(i: Int, j: Int) = km(p[o[i]], p[o[j]])
        var improved = true
        var guard = 0
        while (improved && guard++ < 100) {
            improved = false
            for (i in 1 until n - 1) {
                for (j in i + 1 until n) {
                    val nx = if (j + 1 < n) j + 1 else if (closed) 0 else -1
                    val before = d(i - 1, i) + (if (nx >= 0) d(j, nx) else 0.0)
                    val after = d(i - 1, j) + (if (nx >= 0) d(i, nx) else 0.0)
                    if (after + 1e-9 < before) {
                        o.subList(i, j + 1).reverse()
                        improved = true
                    }
                }
            }
        }
        return o
    }

    /** (km totales, minutos totales) estimados. */
    fun estimate(stops: List<Stop>, roundTrip: Boolean): Pair<Double, Int> {
        val p = stops.filter { it.lat != null && it.lng != null }
        if (p.size < 2) return 0.0 to 0
        var km = 0.0
        for (i in 1 until p.size) km += legKm(p[i - 1], p[i])
        if (roundTrip) km += legKm(p.last(), p.first())
        return km to (km / AVG_KMH * 60 + STOP_MIN * p.size).roundToInt()
    }
}

object Nav {
    /** Enlaces de Google Maps divididos en tramos (máx. 10 puntos: origen + 8 paradas + destino). */
    fun googleMapsUrls(stops: List<Stop>, roundTrip: Boolean): List<String> {
        val pts = stops.filter { it.lat != null && it.lng != null }.map { "${it.lat},${it.lng}" }
        val full = if (roundTrip && pts.size > 1) pts + pts.first() else pts
        val urls = mutableListOf<String>()
        var i = 0
        while (i < full.size - 1) {
            val end = minOf(i + 9, full.size - 1)
            val c = full.subList(i, end + 1)
            var u = "https://www.google.com/maps/dir/?api=1&origin=${c.first()}&destination=${c.last()}&travelmode=driving"
            if (c.size > 2) u += "&waypoints=" + c.subList(1, c.size - 1).joinToString("%7C")
            urls.add(u)
            i = end
        }
        return urls
    }

    fun open(ctx: Context, url: String) {
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(base).setPackage("com.google.android.apps.maps"))
        } catch (e: Exception) {
            try { ctx.startActivity(base) } catch (_: Exception) {}
        }
    }

    /** Una sola parada, con selector de la app de navegación preferida. */
    fun openStop(ctx: Context, s: Stop) {
        val lat = s.lat ?: return
        val lng = s.lng ?: return
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(s.name)})")
        try {
            ctx.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Navegar con…")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }
}
