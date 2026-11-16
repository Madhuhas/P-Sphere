package com.psphere.desktop.smart

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** A town or city a photo was taken in. [id] is stable, e.g. "Hyderabad|IN|Telangana". */
data class Place(val id: String, val city: String, val region: String, val country: String)

/**
 * Turns a photo's GPS position into a place name, offline, using a bundled list of every
 * town over 15,000 people (GeoNames, CC BY 4.0). Nothing is looked up online.
 */
object Places {
    private class City(val name: String, val lat: Double, val lon: Double, val cc: String, val region: String, val pop: Long)

    private val countries = HashMap<String, String>()
    /** Cities by 1-degree square, for a quick nearby search. */
    private val grid: Map<Long, List<City>> by lazy { load() }

    /** The GPS position in a photo file, or null if it has none. Works for JPEG, HEIC, PNG and more. */
    fun position(fileBytes: ByteArray): Pair<Double, Double>? = runCatching {
        val loc = ImageMetadataReader.readMetadata(ByteArrayInputStream(fileBytes))
            .getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation ?: return null
        if (loc.isZero || loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0) null else loc.latitude to loc.longitude
    }.getOrNull()

    /**
     * The place for a position: the most important town within 30 km, favouring big ones nearby.
     * Out in the countryside (mountains, beaches, villages) it's the region instead, e.g. "Bern".
     */
    fun at(lat: Double, lon: Double): Place? {
        val g = grid
        var best: City? = null
        var bestScore = 0.0
        var nearest: City? = null
        var nearestKm = Double.MAX_VALUE
        for (dy in -2..2) for (dx in -2..2) {
            for (c in g[key(floor(lat) + dy, floor(lon) + dx)].orEmpty()) {
                val d = km(lat, lon, c.lat, c.lon)
                if (d < nearestKm) { nearestKm = d; nearest = c }
                if (d > MAX_KM) continue
                val score = c.pop / ((d + 2) * (d + 2))
                if (score > bestScore) { bestScore = score; best = c }
            }
        }
        best?.let { c -> return Place("${c.name}|${c.cc}|${c.region}", c.name, c.region, countries[c.cc] ?: c.cc) }
        val n = nearest?.takeIf { nearestKm <= REGION_KM && it.region.isNotEmpty() } ?: return null
        return Place("|${n.cc}|${n.region}", n.region, "", countries[n.cc] ?: n.cc)
    }

    private fun key(latFloor: Double, lonFloor: Double): Long {
        val lo = ((lonFloor + 540) % 360 - 180) // wrap around the date line
        return (latFloor.toLong() shl 16) or ((lo.toLong() + 180) and 0xFFFF)
    }

    private fun load(): Map<Long, List<City>> {
        val map = HashMap<Long, MutableList<City>>()
        val stream = Places::class.java.getResourceAsStream("/geo/places.tsv.gz") ?: return map
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val f = line.split('\t')
                if (f[0] == "C" && f.size == 3) { countries[f[1]] = f[2]; continue }
                if (f.size < 6) continue
                val c = City(f[0], f[1].toDouble(), f[2].toDouble(), f[3], f[4], f[5].toLongOrNull() ?: 0)
                map.getOrPut(key(floor(c.lat), floor(c.lon))) { ArrayList() } += c
            }
        }
        return map
    }

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = PI / 180
        val a = sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
        return 2 * 6371 * asin(sqrt(a))
    }

    private const val MAX_KM = 30.0
    private const val REGION_KM = 120.0
}
