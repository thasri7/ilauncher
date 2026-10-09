package com.custom.keyboard.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.custom.keyboard.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/** Current conditions and a short daily forecast for the Weather tile. */
data class WeatherReport(
    val place: String,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val code: Int,
    val isDay: Boolean,
    val days: List<Day>,
    /** "C" or "F". */
    val unit: String,
    val fetchedAt: Long
) {
    data class Day(val date: String, val code: Int, val max: Double, val min: Double)

    val today: Day? get() = days.firstOrNull()
}

/** WMO weather interpretation codes, as used by Open-Meteo. */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "—"
    }

    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_m_sun else R.drawable.ic_m_moon
        2, 3 -> R.drawable.ic_m_cloud
        45, 48 -> R.drawable.ic_m_fog
        in 51..67, 80, 81, 82 -> R.drawable.ic_m_rain
        in 71..77, 85, 86 -> R.drawable.ic_m_snow
        in 95..99 -> R.drawable.ic_m_thunder
        else -> R.drawable.ic_m_cloud
    }
}

/** Parses Open-Meteo responses. Kept free of Android types so it can be unit-tested. */
object WeatherParser {
    data class Place(val name: String, val detail: String, val latitude: Double, val longitude: Double)

    fun forecast(json: String, place: String, unit: String, now: Long): WeatherReport {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.optJSONObject("daily")
        val days = ArrayList<WeatherReport.Day>()
        if (daily != null) {
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val max = daily.getJSONArray("temperature_2m_max")
            val min = daily.getJSONArray("temperature_2m_min")
            for (i in 0 until dates.length()) {
                days.add(WeatherReport.Day(dates.getString(i), codes.optInt(i), max.optDouble(i), min.optDouble(i)))
            }
        }
        return WeatherReport(
            place = place,
            temperature = current.getDouble("temperature_2m"),
            feelsLike = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
            humidity = current.optInt("relative_humidity_2m"),
            windSpeed = current.optDouble("wind_speed_10m"),
            code = current.optInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            days = days,
            unit = unit,
            fetchedAt = now
        )
    }

    fun places(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")
            Place(r.getString("name"), detail, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }
}

/**
 * Fetches weather from Open-Meteo (free, no API key) for a city the user picked or for the
 * phone's approximate location, and caches the last report so the tile works offline.
 */
class WeatherRepository(private val context: Context, private val prefs: TilePreferences) {
    companion object {
        const val REFRESH_INTERVAL_MS = 30 * 60_000L
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var refreshing = false

    var report: WeatherReport? = loadCache()
        private set

    val hasLocation: Boolean get() = prefs.weatherLatitude.isNotEmpty() && prefs.weatherLongitude.isNotEmpty()

    val isStale: Boolean
        get() = report.let { it == null || it.unit != prefs.weatherUnit || System.currentTimeMillis() - it.fetchedAt > REFRESH_INTERVAL_MS }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun setPlace(place: WeatherParser.Place, useDevice: Boolean) {
        prefs.weatherPlace = place.name
        prefs.weatherLatitude = place.latitude.toString()
        prefs.weatherLongitude = place.longitude.toString()
        prefs.weatherUseDevice = useDevice
    }

    /** Refreshes the report; [onDone] gets the new report or an error message. */
    fun refresh(onDone: (WeatherReport?, String?) -> Unit) {
        if (refreshing) return
        if (prefs.weatherUseDevice && hasLocationPermission()) {
            locateDevice { place ->
                if (place != null) setPlace(place, useDevice = true)
                fetch(onDone)
            }
        } else {
            fetch(onDone)
        }
    }

    private fun fetch(onDone: (WeatherReport?, String?) -> Unit) {
        val lat = prefs.weatherLatitude
        val lon = prefs.weatherLongitude
        if (lat.isEmpty() || lon.isEmpty()) {
            onDone(null, "Choose a location for weather")
            return
        }
        val unit = prefs.weatherUnit
        val place = prefs.weatherPlace
        refreshing = true
        executor.execute {
            val result = runCatching {
                val url = "$FORECAST_URL?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=5&timezone=auto" +
                    (if (unit == "F") "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "")
                WeatherParser.forecast(get(url), place, unit, System.currentTimeMillis())
            }
            main.post {
                refreshing = false
                val fresh = result.getOrNull()
                if (fresh != null) {
                    report = fresh
                    saveCache(fresh)
                    onDone(fresh, null)
                } else {
                    onDone(report, "Couldn't update the weather. Check your connection.")
                }
            }
        }
    }

    fun searchPlaces(query: String, onDone: (List<WeatherParser.Place>) -> Unit) {
        val q = query.trim()
        if (q.length < 2) {
            onDone(emptyList())
            return
        }
        executor.execute {
            val places = runCatching {
                val lang = Locale.getDefault().language.ifEmpty { "en" }
                WeatherParser.places(get("$GEOCODING_URL?name=${URLEncoder.encode(q, "UTF-8")}&count=8&language=$lang&format=json"))
            }.getOrDefault(emptyList())
            main.post { onDone(places) }
        }
    }

    /** The phone's approximate position and its town name. Needs ACCESS_COARSE_LOCATION. */
    @SuppressLint("MissingPermission")
    fun locateDevice(onDone: (WeatherParser.Place?) -> Unit) {
        if (!hasLocationPermission()) {
            onDone(null)
            return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) {
            onDone(null)
            return
        }
        val last = lm.getProviders(true).mapNotNull { provider ->
            try {
                lm.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.time }
        val fresh = last != null && System.currentTimeMillis() - last.time < 3 * 3_600_000L
        if (last != null && fresh) {
            namePlace(last, onDone)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, executor) { loc ->
                    val best = loc ?: last
                    if (best != null) namePlace(best, onDone) else main.post { onDone(null) }
                }
                return
            } catch (_: Exception) {
            }
        }
        if (last != null) namePlace(last, onDone) else onDone(null)
    }

    private fun namePlace(location: Location, onDone: (WeatherParser.Place?) -> Unit) {
        executor.execute {
            val name = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull() ?: "Current location"
            main.post { onDone(WeatherParser.Place(name, "", location.latitude, location.longitude)) }
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "iLauncher-Start/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun saveCache(r: WeatherReport) {
        prefs.weatherCache = JSONObject().apply {
            put("place", r.place)
            put("temperature", r.temperature)
            put("feelsLike", r.feelsLike)
            put("humidity", r.humidity)
            put("wind", r.windSpeed)
            put("code", r.code)
            put("isDay", r.isDay)
            put("unit", r.unit)
            put("fetchedAt", r.fetchedAt)
            put("days", JSONArray().apply {
                r.days.forEach { d -> put(JSONObject().put("date", d.date).put("code", d.code).put("max", d.max).put("min", d.min)) }
            })
        }.toString()
    }

    private fun loadCache(): WeatherReport? = runCatching {
        val o = JSONObject(prefs.weatherCache.ifEmpty { return null })
        val days = o.optJSONArray("days") ?: JSONArray()
        WeatherReport(
            place = o.getString("place"),
            temperature = o.getDouble("temperature"),
            feelsLike = o.getDouble("feelsLike"),
            humidity = o.getInt("humidity"),
            windSpeed = o.getDouble("wind"),
            code = o.getInt("code"),
            isDay = o.getBoolean("isDay"),
            days = (0 until days.length()).map { i ->
                val d = days.getJSONObject(i)
                WeatherReport.Day(d.getString("date"), d.getInt("code"), d.getDouble("max"), d.getDouble("min"))
            },
            unit = o.getString("unit"),
            fetchedAt = o.getLong("fetchedAt")
        )
    }.getOrNull()
}
