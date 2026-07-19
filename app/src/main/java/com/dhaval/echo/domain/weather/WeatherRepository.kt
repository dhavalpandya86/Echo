package com.dhaval.echo.domain.weather

/** A light snapshot of the current weather where the user is. */
data class Weather(
    val temperatureC: Int,
    val condition: String,   // human phrase, e.g. "Sunny", "Light rain"
    val city: String         // may be blank if it can't be resolved
)

/**
 * Current local weather for the Today briefing. Purely additive and optional —
 * returns null when location permission is absent, offline, or unavailable, so
 * the greeting simply omits the weather line rather than showing an error.
 */
interface WeatherRepository {
    suspend fun currentWeather(): Weather?
}
