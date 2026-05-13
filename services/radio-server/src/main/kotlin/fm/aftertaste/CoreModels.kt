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
data class LyricsResponse(
    val provider: String,
    val trackId: String,
    val lyrics: String? = null
)
