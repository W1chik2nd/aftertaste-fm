package fm.aftertaste

import kotlinx.serialization.Serializable

const val DEFAULT_CANDIDATE_LIMIT = 72
const val SEGMENT_TRACK_COUNT = 3

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
    val needsReview: Boolean = true,
    val lastAnalyzedAt: String? = null
) {
    fun toTaggedTrack(): TaggedTrack {
        val allTags = (moodTags + contextTags + soundTags + useTags)
            .filter { it.confidence >= TAG_CONFIDENCE_RUNTIME_THRESHOLD }
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
    val version: Int = EVIDENCE_SCHEMA_VERSION,
    val generatedAt: String,
    val source: String,
    val playlistId: String,
    val playlistName: String,
    val analysisMode: String,
    val tracks: List<EvidenceTrackAnalysis>
)

@Serializable
data class TasteRules(
    val version: Int = 1,
    val defaultCandidateLimit: Int = DEFAULT_CANDIDATE_LIMIT,
    val segmentTrackCount: Int = SEGMENT_TRACK_COUNT,
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
data class TasteProfileResponse(
    val profileText: String,
    val rules: TasteRules,
    val source: String
)

private const val TAG_CONFIDENCE_RUNTIME_THRESHOLD = 0.35
const val EVIDENCE_SCHEMA_VERSION = 2
