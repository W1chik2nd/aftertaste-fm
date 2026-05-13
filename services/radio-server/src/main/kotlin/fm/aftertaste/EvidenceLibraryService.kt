package fm.aftertaste

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension

private const val DEFAULT_TASTE_TRACK_LIMIT = 50
private const val MAX_TASTE_TRACK_LIMIT = 200
private const val DOMINANT_TAG_LIMIT = 5
private const val CONFIDENCE_DECIMAL_PLACES = 100.0

data class TasteTrackQuery(
    val language: String?,
    val minConfidence: Double?,
    val tag: String?,
    val sort: String?,
    val limit: Int,
    val offset: Int
)

class EvidenceLibraryService(
    private val tastePath: Path = Env.path("TASTE_DATA_DIR", "data/taste"),
    private val profileBuilder: TasteProfileBuilder = TasteProfileBuilder(tastePath)
) {
    private val logger = LoggerFactory.getLogger(EvidenceLibraryService::class.java)
    private val json = HttpClients.sharedJson
    private val tracksRoot = tastePath.resolve("tracks")
    private val aggregatePath = tastePath.resolve("tracks.evidence.json")

    // In-memory cache of every per-track evidence JSON keyed by "provider:id". The first read
    // walks the tracks directory; subsequent reads + exists() / existingKeys() are O(1). The
    // cache is kept consistent with disk by going through save() and rebuildAggregate(); we are
    // the sole writer for data/taste/tracks at runtime, so single-process coherence is enough.
    private var cache: LinkedHashMap<String, EvidenceTrackAnalysis>? = null
    private val cacheMutex = Mutex()

    suspend fun exists(provider: String, id: String): Boolean =
        ensureCache().containsKey(cacheKey(provider, id))

    suspend fun existingKeys(): Set<String> = ensureCache().keys.toSet()

    suspend fun save(track: EvidenceTrackAnalysis) {
        AtomicFiles.writeString(trackPath(track.provider, track.id), json.encodeToString(track) + "\n")
        cacheMutex.withLock {
            cache?.put(cacheKey(track.provider, track.id), track)
        }
    }

    suspend fun delete(provider: String, id: String): Boolean {
        val path = trackPath(provider, id)
        val deleted = withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
        if (deleted) {
            cacheMutex.withLock { cache?.remove(cacheKey(provider, id)) }
            rebuildAggregate()
        }
        return deleted
    }

    suspend fun get(provider: String, id: String): EvidenceTrackAnalysis? =
        ensureCache()[cacheKey(provider, id)]

    suspend fun list(): List<EvidenceTrackAnalysis> = ensureCache().values.toList()

    suspend fun query(query: TasteTrackQuery): TasteTracksResponse {
        val filtered = list()
            .asSequence()
            .filter { query.language == null || it.language.value == query.language }
            .filter { query.tag == null || query.tag in it.allTagNames() }
            .map { it.toTrackView() }
            .filter { query.minConfidence == null || it.confidence >= query.minConfidence }
            .let { sortViews(it, query.sort) }
            .toList()
        val offset = query.offset.coerceAtLeast(0)
        val limit = query.limit.coerceIn(1, MAX_TASTE_TRACK_LIMIT)
        return TasteTracksResponse(
            tracks = filtered.drop(offset).take(limit),
            total = filtered.size
        )
    }

    suspend fun distinctTagNames(): List<String> =
        ensureCache().values
            .flatMap { it.allTagNames() }
            .distinct()
            .sorted()

    suspend fun rebuildAggregate(): EvidencePlaylistAnalysis {
        val tracks = list().sortedWith(
            compareBy<EvidenceTrackAnalysis> { it.artist }.thenBy { it.title }.thenBy { it.id }
        )
        return withContext(Dispatchers.IO) {
            val aggregate = EvidencePlaylistAnalysis(
                generatedAt = nowIso(),
                source = "data/taste/tracks",
                playlistId = "library",
                playlistName = "Aftertaste Library",
                analysisMode = "evidence-v2-per-track",
                tracks = tracks
            )
            AtomicFiles.writeStringBlocking(aggregatePath, json.encodeToString(aggregate) + "\n")
            profileBuilder.write(aggregate)
            aggregate
        }
    }

    private suspend fun ensureCache(): LinkedHashMap<String, EvidenceTrackAnalysis> {
        cache?.let { return it }
        return cacheMutex.withLock {
            cache ?: loadCacheFromDisk().also { cache = it }
        }
    }

    private suspend fun loadCacheFromDisk(): LinkedHashMap<String, EvidenceTrackAnalysis> =
        withContext(Dispatchers.IO) {
            val map = LinkedHashMap<String, EvidenceTrackAnalysis>()
            if (!Files.exists(tracksRoot)) return@withContext map
            Files.walk(tracksRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.extension == "json" }
                    .toList()
                    .forEach { path ->
                        readEvidenceFile(path)?.let { map[cacheKey(it.provider, it.id)] = it }
                    }
            }
            map
        }

    private fun cacheKey(provider: String, id: String): String = "$provider:$id"

    fun queryFromParameters(
        language: String?,
        minConfidence: String?,
        tag: String?,
        sort: String?,
        limit: String?,
        offset: String?
    ): TasteTrackQuery =
        TasteTrackQuery(
            language = language?.takeIf { it.isNotBlank() },
            minConfidence = minConfidence?.toDoubleOrNull(),
            tag = tag?.takeIf { it.isNotBlank() },
            sort = sort?.takeIf { it.isNotBlank() },
            limit = limit?.toIntOrNull() ?: DEFAULT_TASTE_TRACK_LIMIT,
            offset = offset?.toIntOrNull() ?: 0
        )

    private fun trackPath(provider: String, id: String): Path =
        tracksRoot.resolve(safeFileStem(provider)).resolve("${safeFileStem(id)}.json")

    private fun readEvidenceFile(path: Path): EvidenceTrackAnalysis? =
        try {
            json.decodeFromString<EvidenceTrackAnalysis>(Files.readString(path))
        } catch (cause: SerializationException) {
            logger.warn("Skipping invalid evidence file {}: {}", path, cause.message)
            null
        } catch (cause: IOException) {
            logger.warn("Skipping unreadable evidence file {}: {}", path, cause.message)
            null
        }

    private fun EvidenceTrackAnalysis.toTrackView(): TaggedTrackView =
        TaggedTrackView(
            provider = provider,
            id = id,
            title = title,
            artist = artist,
            album = album,
            coverUrl = coverUrl,
            language = language.value,
            dominantTags = dominantTags(),
            scores = TrackScoresView(
                energy = scores.energy.value,
                valence = scores.valence.value,
                night = scores.night.value,
                coding = scores.coding.value,
                skipRisk = scores.skipRisk.value,
                speechiness = scores.speechiness.value,
                emotionalIntensity = scores.emotionalIntensity.value,
                lyricalFocus = scores.lyricalFocus.value,
                mainstreamAppeal = scores.mainstreamAppeal.value
            ),
            confidence = confidence(),
            needsReview = needsReview,
            lastAnalyzedAt = lastAnalyzedAt
        )

    private fun EvidenceTrackAnalysis.dominantTags(): List<String> =
        (moodTags + contextTags + soundTags + useTags)
            .sortedByDescending { it.confidence }
            .map { it.tag }
            .distinct()
            .take(DOMINANT_TAG_LIMIT)

    private fun EvidenceTrackAnalysis.allTagNames(): Set<String> =
        (moodTags + contextTags + soundTags + useTags).map { it.tag }.toSet()

    private fun EvidenceTrackAnalysis.confidence(): Double {
        val tagGroups = listOf(moodTags, contextTags, soundTags, useTags)
            .filter { it.isNotEmpty() }
            .map { group -> group.maxOf { it.confidence } }
        val values = listOf(
            language.confidence,
            scores.energy.confidence,
            scores.valence.confidence,
            scores.night.confidence,
            scores.coding.confidence,
            scores.skipRisk.confidence,
            scores.speechiness.confidence,
            scores.emotionalIntensity.confidence,
            scores.lyricalFocus.confidence,
            scores.mainstreamAppeal.confidence
        ) + tagGroups
        return (values.average() * CONFIDENCE_DECIMAL_PLACES).toInt() / CONFIDENCE_DECIMAL_PLACES
    }

    private fun sortViews(
        tracks: Sequence<TaggedTrackView>,
        sort: String?
    ): Sequence<TaggedTrackView> =
        when (sort) {
            "artist" -> tracks.sortedWith(compareBy<TaggedTrackView> { it.artist }.thenBy { it.title })
            "confidence" -> tracks.sortedByDescending { it.confidence }
            "energy" -> tracks.sortedByDescending { it.scores.energy }
            "night" -> tracks.sortedByDescending { it.scores.night }
            "coding" -> tracks.sortedByDescending { it.scores.coding }
            "recent" -> tracks.sortedByDescending { it.lastAnalyzedAt.orEmpty() }
            else -> tracks.sortedWith(compareBy<TaggedTrackView> { it.title }.thenBy { it.artist })
        }

    private fun nowIso(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
