package com.dhaval.echo.data.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dhaval.echo.domain.weather.Weather
import com.dhaval.echo.domain.weather.WeatherRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Weather from Open-Meteo (free, no API key). Uses the device's last known
 * coarse location and reverse-geocodes the city. Every failure path returns
 * null so the Today briefing just drops the weather line.
 */
@Singleton
class OpenMeteoWeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : WeatherRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun currentWeather(): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasLocationPermission()) return@withContext null
            val loc = lastKnownLocation() ?: return@withContext null
            val current = fetchCurrent(loc.latitude, loc.longitude) ?: return@withContext null
            Weather(
                temperatureC = current.first,
                condition = conditionFor(current.second),
                city = geocodeCity(loc)
            )
        }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun geocodeCity(loc: Location): String = runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault())
            .getFromLocation(loc.latitude, loc.longitude, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            ?: ""
    }.getOrDefault("")

    /** @return (temperatureC, weatherCode) or null. */
    private suspend fun fetchCurrent(lat: Double, lon: Double): Pair<Int, Int>? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&temperature_unit=celsius"
        val body = get(url) ?: return null
        val current = json.parseToJsonElement(body).jsonObject["current"]?.jsonObject ?: return null
        val temp = current["temperature_2m"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val code = current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return temp.roundToInt() to code
    }

    private suspend fun get(url: String): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(if (response.isSuccessful) response.body?.string() else null)
                response.close()
            }
        })
    }

    /** WMO weather codes → a warm, human phrase. */
    private fun conditionFor(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rainy"
        in 71..77 -> "Snowy"
        in 80..82 -> "Rain showers"
        in 85..86 -> "Snow showers"
        in 95..99 -> "Thunderstorms"
        else -> "Mild"
    }

    private companion object { const val TAG = "Weather" }
}
