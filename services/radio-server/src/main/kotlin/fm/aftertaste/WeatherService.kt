package fm.aftertaste

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class WeatherService(
    private val client: HttpClient = HttpClients.shared
) {
    private val logger = LoggerFactory.getLogger(WeatherService::class.java)
    private val json: Json = HttpClients.sharedJson
    private val openWeatherApiKey = Env.value("OPENWEATHER_API_KEY")
    private val openWeatherGeoBaseUrl = Env.value("OPENWEATHER_GEO_BASE_URL") ?: "https://api.openweathermap.org/geo/1.0"
    private val openWeatherBaseUrl = Env.value("OPENWEATHER_BASE_URL") ?: "https://api.openweathermap.org/data/2.5"
    private val units = Env.value("OPENWEATHER_UNITS") ?: "metric"
    private val language = Env.value("OPENWEATHER_LANG") ?: "en"

    suspend fun refresh(location: String): WeatherSnapshot? {
        val resolved = geocode(location) ?: openMeteoGeocode(location) ?: return null
        val current = forecast(resolved.latitude, resolved.longitude)
            ?: openMeteoForecast(resolved.latitude, resolved.longitude)
            ?: return null
        return current.copy(locationName = resolved.name)
    }

    private suspend fun geocode(location: String): ResolvedLocation? {
        val apiKey = openWeatherApiKey?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val response = client.get("$openWeatherGeoBaseUrl/direct") {
                parameter("q", location)
                parameter("limit", 1)
                parameter("appid", apiKey)
            }.body<String>()
            val result = json.parseToJsonElement(response).jsonArray.firstOrNull()?.jsonObject ?: return null
            val name = listOfNotNull(
                result["name"]?.jsonPrimitive?.contentOrNull,
                result["state"]?.jsonPrimitive?.contentOrNull,
                result["country"]?.jsonPrimitive?.contentOrNull
            ).distinct().joinToString(", ")
            ResolvedLocation(
                name = name.ifBlank { location },
                latitude = result["lat"]?.jsonPrimitive?.doubleOrNull ?: return null,
                longitude = result["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
            )
        }.onFailure { logger.debug("OpenWeather geocode failed: {}", it.message) }.getOrNull()
    }

    private suspend fun forecast(latitude: Double, longitude: Double): WeatherSnapshot? {
        val apiKey = openWeatherApiKey?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val response = client.get("$openWeatherBaseUrl/weather") {
                parameter("lat", latitude)
                parameter("lon", longitude)
                parameter("appid", apiKey)
                parameter("units", units)
                parameter("lang", language)
            }.body<String>()
            val root = json.parseToJsonElement(response).jsonObject
            val main = root["main"]?.jsonObject ?: return null
            val wind = root["wind"]?.jsonObject
            val weather = root["weather"]?.jsonArray?.firstOrNull()?.jsonObject
            WeatherSnapshot(
                locationName = root["name"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                latitude = latitude,
                longitude = longitude,
                temperatureC = main["temp"]?.jsonPrimitive?.doubleOrNull ?: return null,
                apparentTemperatureC = main["feels_like"]?.jsonPrimitive?.doubleOrNull,
                precipitationMm = precipitation(root),
                weatherCode = weather?.get("id")?.jsonPrimitive?.intOrNull,
                condition = weather?.get("description")?.jsonPrimitive?.contentOrNull
                    ?: weather?.get("main")?.jsonPrimitive?.contentOrNull
                    ?: "unknown",
                windSpeedKmh = wind?.get("speed")?.jsonPrimitive?.doubleOrNull?.let { speed ->
                    if (units == "imperial") speed * 1.60934 else speed * 3.6
                },
                fetchedAt = OffsetDateTime.now().toString()
            )
        }.onFailure { logger.debug("OpenWeather forecast failed: {}", it.message) }.getOrNull()
    }

    private fun precipitation(root: JsonObject): Double? {
        val rain = root["rain"]?.jsonObject?.get("1h")?.jsonPrimitive?.doubleOrNull
            ?: root["rain"]?.jsonObject?.get("3h")?.jsonPrimitive?.doubleOrNull
        val snow = root["snow"]?.jsonObject?.get("1h")?.jsonPrimitive?.doubleOrNull
            ?: root["snow"]?.jsonObject?.get("3h")?.jsonPrimitive?.doubleOrNull
        return listOfNotNull(rain, snow).takeIf { it.isNotEmpty() }?.sum()
    }

    private suspend fun openMeteoGeocode(location: String): ResolvedLocation? =
        runCatching {
            val response = client.get("https://geocoding-api.open-meteo.com/v1/search") {
                parameter("name", location)
                parameter("count", 1)
                parameter("language", "en")
                parameter("format", "json")
            }.body<String>()
            val root = json.parseToJsonElement(response).jsonObject
            val result = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val name = listOfNotNull(
                result["name"]?.jsonPrimitive?.contentOrNull,
                result["admin1"]?.jsonPrimitive?.contentOrNull,
                result["country"]?.jsonPrimitive?.contentOrNull
            ).distinct().joinToString(", ")
            ResolvedLocation(
                name = name.ifBlank { location },
                latitude = result["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null,
                longitude = result["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
            )
        }.getOrNull()

    private suspend fun openMeteoForecast(latitude: Double, longitude: Double): WeatherSnapshot? =
        runCatching {
            val response = client.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", latitude)
                parameter("longitude", longitude)
                parameter("current", "temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m")
                parameter("timezone", "auto")
            }.body<String>()
            val current = json.parseToJsonElement(response).jsonObject["current"]?.jsonObject ?: return null
            val code = current["weather_code"]?.jsonPrimitive?.intOrNull
            WeatherSnapshot(
                locationName = "unknown",
                latitude = latitude,
                longitude = longitude,
                temperatureC = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: return null,
                apparentTemperatureC = current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull,
                precipitationMm = current["precipitation"]?.jsonPrimitive?.doubleOrNull,
                weatherCode = code,
                condition = describeWeatherCode(code),
                windSpeedKmh = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull,
                fetchedAt = OffsetDateTime.now().toString()
            )
        }.getOrNull()

    private data class ResolvedLocation(
        val name: String,
        val latitude: Double,
        val longitude: Double
    )
}

private fun describeWeatherCode(code: Int?): String =
    when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unknown"
    }
