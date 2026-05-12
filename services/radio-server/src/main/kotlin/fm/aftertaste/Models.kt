package fm.aftertaste

import kotlinx.serialization.Serializable

@Serializable
data class HostConfig(
    val hostLanguage: String = "en-US",
    val hostStyle: String = "calm late-night radio",
    val hostName: String = "Aftertaste",
    val segmentSpeechMode: String = "between_segments"
)

@Serializable
data class Track(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val streamUrl: String? = null,
    val unavailableReason: String? = null
)

@Serializable
data class TaggedTrack(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
    val language: String = "unknown",
    val energy: Double = 0.5,
    val valence: Double = 0.5,
    val nightScore: Double = 0.5,
    val codingScore: Double = 0.5,
    val skipRisk: Double = 0.2,
    val notes: String? = null
) {
    fun toTrack(): Track = Track(
        provider = provider,
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl
    )
}

@Serializable
data class EvidenceValueString(
    val value: String,
    val confidence: Double,
    val evidence: List<String> = emptyList()
)

@Serializable
data class EvidenceValueDouble(
    val value: Double,
    val confidence: Double,
    val evidence: List<String> = emptyList()
)

@Serializable
data class EvidenceTag(
    val tag: String,
    val confidence: Double,
    val evidence: List<String> = emptyList()
)

@Serializable
data class EvidenceScores(
    val energy: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val valence: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val night: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val coding: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val skipRisk: EvidenceValueDouble = EvidenceValueDouble(0.2, 0.0),
    val danceability: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val acousticness: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val lyricDensity: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val vocalPresence: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val familiarity: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0),
    val intensity: EvidenceValueDouble = EvidenceValueDouble(0.5, 0.0)
)

@Serializable
data class TrackEvidenceState(
    val metadata: Boolean = true,
    val lyrics: Boolean = false,
    val audioFeatures: Boolean = false,
    val userBehavior: Boolean = false,
    val manual: Boolean = false,
    val model: Boolean = false
)

@Serializable
data class EvidenceTrackAnalysis(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val language: EvidenceValueString = EvidenceValueString("unknown", 0.0),
    val moodTags: List<EvidenceTag> = emptyList(),
    val contextTags: List<EvidenceTag> = emptyList(),
    val soundTags: List<EvidenceTag> = emptyList(),
    val useTags: List<EvidenceTag> = emptyList(),
    val scores: EvidenceScores = EvidenceScores(),
    val evidence: TrackEvidenceState = TrackEvidenceState(),
    val lyricExcerpt: String? = null,
    val notes: String? = null,
    val needsReview: Boolean = true
) {
    fun toTaggedTrack(): TaggedTrack {
        val allTags = (moodTags + contextTags + soundTags + useTags)
            .filter { it.confidence >= 0.35 }
            .map { it.tag }
            .distinct()
        return TaggedTrack(
            provider = provider,
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = coverUrl,
            tags = allTags,
            language = language.value,
            energy = scores.energy.value,
            valence = scores.valence.value,
            nightScore = scores.night.value,
            codingScore = scores.coding.value,
            skipRisk = scores.skipRisk.value,
            notes = notes
        )
    }
}

@Serializable
data class EvidencePlaylistAnalysis(
    val version: Int = 2,
    val generatedAt: String,
    val source: String,
    val playlistId: String,
    val playlistName: String,
    val analysisMode: String,
    val tracks: List<EvidenceTrackAnalysis>
)

@Serializable
data class ImportedLyricsFile(
    val importedAt: String,
    val source: String,
    val playlistId: String,
    val playlistName: String,
    val lyricsByTrackId: Map<String, String?>
)

@Serializable
data class TasteRules(
    val version: Int = 1,
    val defaultCandidateLimit: Int = 72,
    val segmentTrackCount: Int = 3,
    val preferredTags: List<String> = emptyList(),
    val avoidTags: List<String> = emptyList(),
    val moodAliases: Map<String, List<String>> = emptyMap(),
    val artistAliases: Map<String, List<String>> = emptyMap()
)

data class TasteProfile(
    val profileText: String,
    val rules: TasteRules,
    val tracks: List<TaggedTrack>,
    val source: String
)

@Serializable
data class StreamUrl(
    val provider: String,
    val trackId: String,
    val url: String? = null,
    val expiresAt: String? = null,
    val quality: String = "unknown",
    val reason: String = "unknown"
)

@Serializable
data class Playlist(
    val provider: String,
    val id: String,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList()
)

@Serializable
data class RecommendationContext(
    val mood: String? = null,
    val localTime: String? = null,
    val hostLanguage: String = "en-US",
    val intent: String = "daily_show",
    val recentSignals: List<String> = emptyList(),
    val weather: WeatherSnapshot? = null,
    val variationSeed: String? = null
)

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
    val weather: WeatherSnapshot? = null
)

@Serializable
data class LocationRequest(val location: String)

@Serializable
data class SettingsResponse(
    val weatherLocation: String? = null,
    val weather: WeatherSnapshot? = null
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
    val version: String = "0.1.0"
)

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class AgentChatResponse(
    val message: String,
    val mode: String
)

@Serializable
data class ImportPlaylistRequest(val source: String)

@Serializable
data class ImportedPlaylistFile(
    val importedAt: String,
    val source: String,
    val playlist: Playlist
)

@Serializable
data class TaggedPlaylistDraft(
    val importedAt: String,
    val source: String,
    val playlistId: String,
    val playlistName: String,
    val tracks: List<TaggedTrack>
)

@Serializable
data class ImportPlaylistResponse(
    val playlist: Playlist,
    val importedAt: String,
    val rawPath: String,
    val taggedDraftPath: String,
    val lyricsPath: String,
    val trackCount: Int,
    val nextStep: String
)

@Serializable
data class AgentSignal(
    val label: String,
    val value: String
)

@Serializable
data class AgentTrace(
    val mode: String,
    val summary: String,
    val contextWindow: List<String>,
    val routing: List<String>,
    val recommendationStrategy: List<String>,
    val signals: List<AgentSignal>
)

@Serializable
data class LlmPlanSegment(
    val title: String,
    val hostScript: String,
    val trackIds: List<String>
)

@Serializable
data class LlmShowPlan(
    val title: String,
    val rationale: String,
    val segments: List<LlmPlanSegment>
)

@Serializable
data class PlanResponse(
    val showPlan: ShowPlan,
    val playback: PlaybackState,
    val agentTrace: AgentTrace? = null
)

@Serializable
data class HostVoiceAsset(
    val script: String,
    val audioUrl: String? = null,
    val cacheKey: String
)

@Serializable
data class StoredState(
    val showPlan: ShowPlan? = null,
    val playback: PlaybackState = PlaybackState(),
    val settings: UserSettings = UserSettings()
)
