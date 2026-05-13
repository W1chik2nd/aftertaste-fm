package fm.aftertaste

import kotlinx.serialization.Serializable

@Serializable
data class ImportPlaylistRequest(val source: String)

@Serializable
data class ImportedPlaylistFile(
    val importedAt: String,
    val source: String,
    val playlist: Playlist
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
data class TaggedPlaylistDraft(
    val importedAt: String,
    val source: String,
    val playlistId: String,
    val playlistName: String,
    val tracks: List<TaggedTrack>
)

@Serializable
data class TrackSummary(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null
)

@Serializable
data class ImportPlaylistResponse(
    val slug: String,
    val playlistId: String,
    val name: String,
    val importedAt: String,
    val trackCount: Int,
    val ignoredDuplicateCount: Int = 0,
    val lyricsFetched: Int,
    val lyricsMissing: Int,
    val rawPath: String,
    val taggedDraftPath: String,
    val lyricsPath: String,
    val nextStep: String
)

@Serializable
data class ImportRecord(
    val slug: String,
    val playlistId: String,
    val name: String,
    val trackCount: Int,
    val importedAt: String,
    val analyzedAt: String? = null,
    val status: String,
    val analyzedTrackCount: Int,
    val pendingAnalysisCount: Int
)

@Serializable
data class ImportDetail(
    val slug: String,
    val playlistId: String,
    val name: String,
    val trackCount: Int,
    val importedAt: String,
    val analyzedAt: String? = null,
    val status: String,
    val analyzedTrackCount: Int,
    val pendingAnalysisCount: Int,
    val tracks: List<TrackSummary>
)

@Serializable
data class AnalyzeImportRequest(
    val force: Boolean = false,
    val trackIds: List<String>? = null
)

@Serializable
data class AnalyzeJobStartResponse(
    val jobId: String,
    val estimatedCalls: Int,
    val estimatedCostUsd: Double? = null,
    val model: String
)

@Serializable
data class AnalysisJobError(
    val trackId: String,
    val message: String
)

@Serializable
data class AnalysisJobView(
    val jobId: String,
    val status: String,
    val processed: Int,
    val total: Int,
    val current: TrackSummary? = null,
    val errors: List<AnalysisJobError> = emptyList(),
    val startedAt: String,
    val finishedAt: String? = null
)

@Serializable
data class TaggedTrackView(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverUrl: String? = null,
    val language: String,
    val dominantTags: List<String>,
    val scores: TrackScoresView,
    val confidence: Double,
    val needsReview: Boolean,
    val lastAnalyzedAt: String? = null
)

@Serializable
data class TrackScoresView(
    val energy: Double,
    val valence: Double,
    val night: Double,
    val coding: Double,
    val skipRisk: Double,
    val speechiness: Double,
    val emotionalIntensity: Double,
    val lyricalFocus: Double,
    val mainstreamAppeal: Double
)

@Serializable
data class TasteTracksResponse(
    val tracks: List<TaggedTrackView>,
    val total: Int
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class TasteTagsResponse(
    val tags: List<String>
)

@Serializable
data class ImportEvidenceJsonRequest(
    val content: String,
    val sourceName: String? = null
)

@Serializable
data class ImportEvidenceJsonResponse(
    val importedTrackCount: Int,
    val ignoredDuplicateCount: Int,
    val totalTrackCount: Int,
    val sourceName: String? = null
)

@Serializable
data class DeleteImportResponse(
    val slug: String,
    val deleted: Boolean,
    val deletedTrackEvidenceCount: Int
)

@Serializable
data class DeleteTrackEvidenceResponse(
    val provider: String,
    val id: String,
    val deleted: Boolean
)
