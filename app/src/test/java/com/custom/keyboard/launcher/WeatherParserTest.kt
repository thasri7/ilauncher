package com.custom.keyboard.launcher

import com.custom.keyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherParserTest {
    // Trimmed real Open-Meteo responses.
    private val forecast = """
        {"latitude":51.5,"longitude":-0.12,
         "current":{"time":"2026-10-08T14:00","temperature_2m":14.3,"apparent_temperature":12.9,
                    "relative_humidity_2m":71,"weather_code":61,"wind_speed_10m":18.4,"is_day":1},
         "daily":{"time":["2026-10-08","2026-10-09","2026-10-10"],
                  "weather_code":[61,3,0],
                  "temperature_2m_max":[15.1,16.8,18.2],
                  "temperature_2m_min":[9.4,8.7,10.1]}}
    """.trimIndent()

    private val search = """
        {"results":[
          {"id":2643743,"name":"London","latitude":51.50853,"longitude":-0.12574,"country":"United Kingdom","admin1":"England"},
          {"id":6058560,"name":"London","latitude":42.98339,"longitude":-81.23304,"country":"Canada","admin1":"Ontario"}]}
    """.trimIndent()

    @Test
    fun parsesCurrentConditionsAndDays() {
        val r = WeatherParser.forecast(forecast, "London", "C", now = 1000L)
        assertEquals(14.3, r.temperature, 0.001)
        assertEquals(12.9, r.feelsLike, 0.001)
        assertEquals(71, r.humidity)
        assertEquals(61, r.code)
        assertTrue(r.isDay)
        assertEquals(3, r.days.size)
        assertEquals(WeatherReport.Day("2026-10-09", 3, 16.8, 8.7), r.days[1])
        assertEquals(15.1, r.today!!.max, 0.001)
        assertEquals(1000L, r.fetchedAt)
    }

    @Test
    fun parsesPlaceSearch() {
        val places = WeatherParser.places(search)
        assertEquals(2, places.size)
        assertEquals("England, United Kingdom", places[0].detail)
        assertEquals(-81.23304, places[1].longitude, 0.00001)
    }

    @Test
    fun noResultsMeansEmptyList() {
        assertEquals(emptyList<WeatherParser.Place>(), WeatherParser.places("""{"generationtime_ms":0.5}"""))
    }

    @Test
    fun weatherCodesMapToConditionsAndIcons() {
        assertEquals("Clear", WeatherCodes.describe(0))
        assertEquals("Thunderstorm", WeatherCodes.describe(95))
        assertEquals(R.drawable.ic_m_moon, WeatherCodes.icon(0, isDay = false))
        assertEquals(R.drawable.ic_m_rain, WeatherCodes.icon(81, isDay = true))
        assertEquals(R.drawable.ic_m_snow, WeatherCodes.icon(73, isDay = true))
    }
}
