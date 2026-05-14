package fm.aftertaste

import kotlinx.serialization.Serializable

@Serializable
data class HostConfig(
    val hostLanguage: String = DEFAULT_HOST_LANGUAGE,
    val hostStyle: String = "calm late-night radio",
    val hostName: String = "Aftertaste",
    val segmentSpeechMode: String = "between_segments"
)

internal const val DEFAULT_HOST_LANGUAGE = "en-US"
internal const val CHINESE_HOST_LANGUAGE = "zh-CN"
internal val SUPPORTED_HOST_LANGUAGES = setOf(DEFAULT_HOST_LANGUAGE, CHINESE_HOST_LANGUAGE)

internal fun requireSupportedHostLanguage(hostLanguage: String): String {
    val cleaned = hostLanguage.trim().ifBlank { DEFAULT_HOST_LANGUAGE }
    require(cleaned in SUPPORTED_HOST_LANGUAGES) {
        "Unsupported hostLanguage '$cleaned'. Supported values: ${SUPPORTED_HOST_LANGUAGES.joinToString(", ")}."
    }
    return cleaned
}

/**
 * Single source of truth for "is this a Chinese-speaking host". Host language tags are BCP-47
 * style (`zh-CN`, `zh-Hant`, ...); everything Chinese-related branches on this, not on `== "zh-CN"`.
 */
internal fun isChineseHostLanguage(hostLanguage: String): Boolean =
    hostLanguage.startsWith("zh", ignoreCase = true)

@Serializable
data class Track(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val playCount: Int? = null,
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
