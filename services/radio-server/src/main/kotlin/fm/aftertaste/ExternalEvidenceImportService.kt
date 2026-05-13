package fm.aftertaste

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.math.roundToInt

class ExternalEvidenceImportException(message: String) : RuntimeException(message)

class ExternalEvidenceImportService(
    private val evidence: EvidenceLibraryService
) {
    private val json = HttpClients.sharedJson

    suspend fun importJson(request: ImportEvidenceJsonRequest): ImportEvidenceJsonResponse {
        val parsed = parseTracks(request.content)
        val quality = checkQuality(parsed)
        if (quality.blockingIssues.isNotEmpty()) {
            throw ExternalEvidenceImportException(
                "External analysis JSON failed quality checks: ${quality.blockingIssues.joinToString("; ")}"
            )
        }
        val existing = evidence.list().map { it.identity() }.toMutableSet()
        val imported = mutableListOf<EvidenceTrackAnalysis>()
        for (track in parsed) {
            if (existing.add(track.identity())) {
                evidence.save(track)
                imported += track
            }
        }
        if (imported.isNotEmpty()) evidence.rebuildAggregate()
        return ImportEvidenceJsonResponse(
            importedTrackCount = imported.size,
            ignoredDuplicateCount = parsed.size - imported.size,
            totalTrackCount = parsed.size,
            sourceName = request.sourceName,
            qualityWarnings = quality.warnings
        )
    }

    private fun parseTracks(content: String): List<EvidenceTrackAnalysis> {
        val element = try {
            json.parseToJsonElement(content)
        } catch (cause: SerializationException) {
            throw ExternalEvidenceImportException("Invalid JSON: ${cause.message}")
        }
        return when (element) {
            is JsonObject -> parseObject(element)
            is JsonArray -> json.decodeFromString(ListSerializer(EvidenceTrackAnalysis.serializer()), content)
            else -> throw ExternalEvidenceImportException("Expected a tracks.evidence.json object or an array of tracks.")
        }.also { tracks ->
            if (tracks.isEmpty()) throw ExternalEvidenceImportException("No tracks found in JSON.")
        }
    }

    private fun parseObject(element: JsonObject): List<EvidenceTrackAnalysis> {
        val tracks = element["tracks"]
            ?: throw ExternalEvidenceImportException("JSON object must include a tracks array.")
        return try {
            json.decodeFromJsonElement(ListSerializer(EvidenceTrackAnalysis.serializer()), tracks)
        } catch (cause: SerializationException) {
            throw ExternalEvidenceImportException("JSON tracks do not match EvidenceTrackAnalysis: ${cause.message}")
        }
    }

    private fun checkQuality(tracks: List<EvidenceTrackAnalysis>): EvidenceQualityResult {
        if (tracks.size < QUALITY_BATCH_TRACK_COUNT) return EvidenceQualityResult()
        val blocking = buildList {
            repeatedScoreIssue(tracks)?.let(::add)
            repeatedHighSpeechinessIssue(tracks)?.let(::add)
            tagCoverageIssue("contextTags", tracks) { it.contextTags }?.let(::add)
            tagCoverageIssue("soundTags", tracks) { it.soundTags }?.let(::add)
            tagCoverageIssue("useTags", tracks) { it.useTags }?.let(::add)
            repeatedNotesIssue(tracks)?.let(::add)
        }
        val warnings = buildList {
            dominantTagWarning("moodTags", tracks) { it.moodTags }?.let(::add)
        }
        return EvidenceQualityResult(blocking, warnings)
    }

    private fun repeatedScoreIssue(tracks: List<EvidenceTrackAnalysis>): String? {
        val repeated = SCORE_VARIATION_READERS.count { (_, reader) ->
            tracks.map { roundedScore(reader(it.scores).value) }.toSet().size == 1
        }
        return if (repeated >= MAX_REPEATED_SCORE_FIELDS) {
            "$repeated score fields are identical across the batch; analyze each track instead of applying one playlist template."
        } else {
            null
        }
    }

    private fun repeatedHighSpeechinessIssue(tracks: List<EvidenceTrackAnalysis>): String? {
        val values = tracks.map { roundedScore(it.scores.speechiness.value) }.toSet()
        val speechiness = tracks.first().scores.speechiness.value
        return if (values.size == 1 && speechiness >= HIGH_SPEECHINESS_VALUE) {
            "speechiness is the same high value for every track; use high speechiness only for rap or spoken-word density."
        } else {
            null
        }
    }

    private fun tagCoverageIssue(
        label: String,
        tracks: List<EvidenceTrackAnalysis>,
        tags: (EvidenceTrackAnalysis) -> List<EvidenceTag>
    ): String? {
        val covered = tracks.count { tags(it).isNotEmpty() }.toDouble() / tracks.size
        return if (covered < MIN_TAG_COVERAGE_RATIO) {
            "$label coverage is too low; most externally analyzed tracks need useful tags in this dimension."
        } else {
            null
        }
    }

    private fun repeatedNotesIssue(tracks: List<EvidenceTrackAnalysis>): String? {
        val repeated = tracks
            .mapNotNull { (it.analysisNotes?.summary ?: it.notes)?.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .values
            .maxOrNull() ?: return null
        val ratio = repeated.toDouble() / tracks.size
        return if (ratio >= MAX_REPEATED_NOTES_RATIO) {
            "analysis notes are too repetitive; each track needs its own short evidence-based summary."
        } else {
            null
        }
    }

    private fun dominantTagWarning(
        label: String,
        tracks: List<EvidenceTrackAnalysis>,
        tags: (EvidenceTrackAnalysis) -> List<EvidenceTag>
    ): String? {
        val topCount = tracks.flatMap(tags).groupingBy { it.tag }.eachCount().values.maxOrNull() ?: return null
        return if (topCount == tracks.size) {
            "$label has a tag applied to every track; check that this is intentional and not playlist-level templating."
        } else {
            null
        }
    }

    private fun roundedScore(value: Double): Int = (value * SCORE_ROUNDING_FACTOR).roundToInt()
}

private data class EvidenceQualityResult(
    val blockingIssues: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

private const val QUALITY_BATCH_TRACK_COUNT = 8
private const val MAX_REPEATED_SCORE_FIELDS = 4
private const val SCORE_ROUNDING_FACTOR = 100
private const val HIGH_SPEECHINESS_VALUE = 0.35
private const val MIN_TAG_COVERAGE_RATIO = 0.5
private const val MAX_REPEATED_NOTES_RATIO = 0.8

private val SCORE_VARIATION_READERS: List<Pair<String, (EvidenceScores) -> EvidenceValueDouble>> = listOf(
    "energy" to { it.energy },
    "valence" to { it.valence },
    "night" to { it.night },
    "coding" to { it.coding },
    "skipRisk" to { it.skipRisk },
    "danceability" to { it.danceability },
    "acousticness" to { it.acousticness },
    "speechiness" to { it.speechiness },
    "emotionalIntensity" to { it.emotionalIntensity },
    "mainstreamAppeal" to { it.mainstreamAppeal }
)
