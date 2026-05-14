package fm.aftertaste

import kotlinx.serialization.Serializable

@Serializable
data class RecommendationContext(
    val mood: String? = null,
    val localTime: String? = null,
    val hostLanguage: String = "en-US",
    val intent: String = "daily_show",
    val routing: RoutingIntent = RoutingIntent(),
    val memory: ContextMemory = ContextMemory(),
    val weather: WeatherSnapshot? = null,
    val variationSeed: String? = null,
    val stationStyle: StationStyle? = null
)

@Serializable
data class ContextMemory(
    val recentPlans: List<String> = emptyList(),
    val recentPlays: List<RecentPlay> = emptyList(),
    val recentMessages: List<RecentMessage> = emptyList()
) {
    fun isEmpty(): Boolean =
        recentPlans.isEmpty() && recentPlays.isEmpty() && recentMessages.isEmpty()
}

@Serializable
data class RecentPlay(
    val action: String,
    val title: String,
    val artist: String? = null
)

@Serializable
data class RecentMessage(
    val role: String,
    val content: String
)

@Serializable
data class RoutingIntent(
    val language: String? = null,
    val energy: String? = null,
    val routine: String? = null,
    val moodTag: String? = null,
    val avoid: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val extraTags: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        language == null && energy == null && routine == null && moodTag == null &&
            avoid.isEmpty() && artists.isEmpty() && extraTags.isEmpty()
}

@Serializable
data class WeatherSnapshot(
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val temperatureC: Double,
    val apparentTemperatureC: Double? = null,
    val precipitationMm: Double? = null,
    val weatherCode: Int? = null,
    val condition: String = "unknown",
    val windSpeedKmh: Double? = null,
    val fetchedAt: String
)

@Serializable
data class UserSettings(
    val weatherLocation: String? = null,
    val weather: WeatherSnapshot? = null,
    // null = follow the HOST_LANGUAGE env default; set = a runtime override chosen in Settings.
    val hostLanguage: String? = null
)

@Serializable
data class LocationRequest(val location: String)

@Serializable
data class HostLanguageRequest(val hostLanguage: String)

@Serializable
data class SettingsResponse(
    val weatherLocation: String? = null,
    val weather: WeatherSnapshot? = null,
    val hostLanguage: String = "en-US",
    val integrations: List<IntegrationStatus> = emptyList()
)

@Serializable
data class IntegrationStatus(
    val id: String,
    val label: String,
    val configured: Boolean
)

@Serializable
data class ShowSegment(
    val id: String,
    val title: String,
    val hostScript: String,
    val tracks: List<Track>
)

@Serializable
data class ShowPlan(
    val id: String,
    val title: String,
    val generatedAt: String,
    val hostConfig: HostConfig,
    val segments: List<ShowSegment>
)

@Serializable
data class QueueItem(
    val id: String,
    val type: String,
    val segmentId: String? = null,
    val segmentTitle: String? = null,
    val hostName: String? = null,
    val hostLanguage: String? = null,
    val hostScript: String? = null,
    val ttsUrl: String? = null,
    val ttsCacheKey: String? = null,
    val track: Track? = null
)

@Serializable
data class PlaybackState(
    val currentItem: QueueItem? = null,
    val currentIndex: Int = 0,
    val queue: List<QueueItem> = emptyList(),
    val showTitle: String? = null,
    val segmentTitle: String? = null,
    val isPlaying: Boolean = false,
    val progressMs: Long = 0,
    val durationMs: Long? = null,
    val hostLanguage: String = "en-US"
)

@Serializable
data class HealthResponse(
    val status: String,
    val provider: String,
    val hostConfig: HostConfig,
    val stationStyle: StationStyle,
    val version: String = "0.1.0"
)

@Serializable
data class StoredState(
    val showPlan: ShowPlan? = null,
    val playback: PlaybackState = PlaybackState(),
    val settings: UserSettings = UserSettings()
)
