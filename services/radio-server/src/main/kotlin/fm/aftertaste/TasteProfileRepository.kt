package fm.aftertaste

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.random.Random

class TasteProfileRepository(
    private val tastePath: Path = Env.path("TASTE_DATA_DIR", "data/taste"),
    private val examplePath: Path = Env.path("TASTE_EXAMPLE_DIR", "data/taste.example")
) {
    private val logger = LoggerFactory.getLogger(TasteProfileRepository::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): TasteProfile {
        val root = when {
            Files.exists(tastePath.resolve("tracks.evidence.json")) -> tastePath
            Files.exists(tastePath.resolve("tracks.json")) -> tastePath
            Files.exists(examplePath.resolve("tracks.json")) -> examplePath
            else -> null
        }
        if (root == null) {
            return TasteProfile(
                profileText = "No taste profile found. Using provider recommendations.",
                rules = TasteRules(),
                tracks = emptyList(),
                source = "none"
            )
        }

        val profileText = readStringIfExists(root.resolve("profile.md"))
            ?: "Example taste profile loaded."
        val rules = readStringIfExists(root.resolve("rules.json"))
            ?.let { content -> decodeOrNull<TasteRules>(content, root.resolve("rules.json")) }
            ?: TasteRules()
        val tracks = loadTracks(root)

        return TasteProfile(
            profileText = profileText,
            rules = rules,
            tracks = tracks,
            source = root.toString()
        )
    }

    private fun loadTracks(root: Path): List<TaggedTrack> {
        readStringIfExists(root.resolve("tracks.evidence.json"))
            ?.let { content ->
                decodeOrNull<EvidencePlaylistAnalysis>(content, root.resolve("tracks.evidence.json"))
                    ?.tracks
                    ?.filterNot {
                        it.needsReview && it.evidence.lyrics.not() && it.evidence.manual.not() && it.evidence.model.not()
                    }
                    ?.map { it.toTaggedTrack() }
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        readStringIfExists(root.resolve("tracks.json"))
            ?.let { content ->
                decodeOrNull(content, root.resolve("tracks.json"), ListSerializer(TaggedTrack.serializer()))
            }
            ?.let { return it }

        return emptyList()
    }

    private inline fun <reified T> decodeOrNull(content: String, path: Path): T? =
        try {
            json.decodeFromString<T>(content)
        } catch (cause: SerializationException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        } catch (cause: IllegalArgumentException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        }

    private fun decodeOrNull(
        content: String,
        path: Path,
        serializer: kotlinx.serialization.KSerializer<List<TaggedTrack>>
    ): List<TaggedTrack>? =
        try {
            json.decodeFromString(serializer, content)
        } catch (cause: SerializationException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        } catch (cause: IllegalArgumentException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        }
}

class CandidateSelector(private val repository: TasteProfileRepository) {
    fun select(context: RecommendationContext, profile: TasteProfile = repository.load()): CandidateSelection {
        if (profile.tracks.isEmpty()) {
            return CandidateSelection(emptyList(), profile, emptyList())
        }

        val routing = context.routing
        val desiredTags = desiredTags(routing, profile.rules)
        val scopedTracks = scopedTracks(profile.tracks, routing)
            .distinctBy { "${normalizeForMatch(it.artist)}|${normalizeForMatch(it.title)}" }
        val scored = scopedTracks.map { tagged ->
            ScoredTaggedTrack(tagged, score(tagged, desiredTags, routing, profile.rules))
        }
            .sortedWith(
                compareByDescending<ScoredTaggedTrack> { it.score }
                    .thenBy { it.track.artist }
                    .thenBy { it.track.title }
            )

        val diversified = diversifyArtists(scored)
        val limit = profile.rules.defaultCandidateLimit.coerceIn(18, 96)
        val selected = exploreCandidates(diversified, limit, context)
        return CandidateSelection(
            tracks = selected.map { it.track },
            profile = profile,
            desiredTags = desiredTags
        )
    }

    private fun desiredTags(routing: RoutingIntent, rules: TasteRules): List<String> {
        val tags = linkedSetOf<String>()
        when (routing.routine) {
            "late-night-coding" -> tags += listOf("coding", "focus", "late-night")
            "commute" -> tags += listOf("commute")
            "wind-down" -> tags += listOf("wind-down", "sleep")
        }
        when (routing.energy) {
            "low" -> tags += listOf("quiet", "soft", "low-energy")
            "high" -> tags += listOf("energy-lift", "upbeat")
        }
        when (routing.language) {
            "en" -> tags += listOf("en", "english", "western-pop")
            "zh-CN" -> tags += listOf("zh-CN", "mandarin", "国语")
            "zh" -> tags += listOf("chinese-indie", "zh-CN", "indie")
            "yue" -> tags += listOf("cantopop", "yue")
        }
        when (routing.moodTag) {
            "rain" -> tags += listOf("rain", "rainy")
            "less-sad" -> tags += listOf("not-too-sad", "warm", "soft")
            "sad" -> tags += listOf("melancholic", "wistful")
            "uplift" -> tags += listOf("uplift", "hopeful")
        }
        tags += routing.artists
        tags += routing.extraTags
        if (tags.isEmpty()) tags += rules.preferredTags
        return tags.toList()
    }

    private fun score(
        track: TaggedTrack,
        desiredTags: List<String>,
        routing: RoutingIntent,
        rules: TasteRules
    ): Double {
        val normalizedTags = track.tags.map { it.lowercase() }.toSet()
        val desired = desiredTags.map { it.lowercase() }.toSet()
        val tagScore = desired.count { it in normalizedTags || it == track.language.lowercase() } * 1.25
        val avoidPenalty = rules.avoidTags.count { it.lowercase() in normalizedTags } * 1.4
        val languageScore = when (routing.language) {
            "zh", "zh-CN" -> if (track.language.startsWith("zh")) 1.8 else 0.0
            "en" -> if (track.language == "en") 1.2 else 0.0
            "yue" -> if (track.language == "yue") 1.6 else 0.0
            else -> 0.0
        }
        val energyTarget = when (routing.energy) {
            "low" -> 0.28
            "high" -> 0.68
            else -> 0.4
        }
        val energyFit = 1.0 - abs(track.energy - energyTarget)
        val codingBoost = if (routing.routine == "late-night-coding") track.codingScore else 0.0
        val nightBoost = track.nightScore * 0.7
        val valenceBoost = if (routing.moodTag == "less-sad") track.valence * 0.8 else 0.0
        return tagScore + languageScore + energyFit + codingBoost + nightBoost + valenceBoost - track.skipRisk - avoidPenalty
    }

    private fun scopedTracks(tracks: List<TaggedTrack>, routing: RoutingIntent): List<TaggedTrack> {
        val artistScoped = if (routing.artists.isNotEmpty()) {
            val requested = routing.artists.map { normalizeForMatch(it) }
            tracks.filter { track ->
                val artistKey = normalizeForMatch(track.artist)
                requested.any { it.isNotBlank() && (it in artistKey || artistKey in it) }
            }
        } else {
            emptyList()
        }
        val baseTracks = artistScoped.takeIf { it.size >= 3 } ?: tracks

        return when (routing.language) {
            "en" -> baseTracks.filter { it.language == "en" }.takeIf { it.size >= 6 }
                ?: artistScoped.takeIf { it.size >= 3 } ?: baseTracks
            "zh-CN" -> baseTracks.filter { it.language == "zh-CN" || it.language == "zh" }.takeIf { it.size >= 6 }
                ?: artistScoped.takeIf { it.size >= 3 } ?: baseTracks
            "zh" -> baseTracks.filter { it.language.startsWith("zh") || it.language == "yue" }.takeIf { it.size >= 6 }
                ?: artistScoped.takeIf { it.size >= 3 } ?: baseTracks
            "yue" -> baseTracks.filter { it.language == "yue" }.takeIf { it.size >= 6 }
                ?: artistScoped.takeIf { it.size >= 3 } ?: baseTracks
            else -> baseTracks
        }
    }

    private fun diversifyArtists(scored: List<ScoredTaggedTrack>): List<ScoredTaggedTrack> {
        val selected = mutableListOf<ScoredTaggedTrack>()
        val deferred = ArrayDeque<ScoredTaggedTrack>()
        scored.forEach { candidate ->
            if (selected.takeLast(2).none { it.track.artist == candidate.track.artist }) {
                selected += candidate
            } else {
                deferred += candidate
            }
        }
        return selected + deferred
    }

    private fun exploreCandidates(
        ranked: List<ScoredTaggedTrack>,
        limit: Int,
        context: RecommendationContext
    ): List<ScoredTaggedTrack> {
        val random = Random("${context.mood}-${context.localTime}-${context.variationSeed}".hashCode())
        if (ranked.size <= limit) return rotateScoreBands(ranked, random)

        val anchorCount = (limit / 4).coerceIn(6, 18)
        val explorationWindow = (limit * 4).coerceAtMost(ranked.size)
        val anchorWindow = (anchorCount * 2).coerceAtMost(ranked.size)
        val anchors = ranked
            .take(anchorWindow)
            .shuffled(random)
            .take(anchorCount)
        val exploration = ranked
            .drop(anchorWindow)
            .take((explorationWindow - anchorWindow).coerceAtLeast(0))
            .shuffled(random)
            .take(limit - anchorCount)

        return diversifyArtists(anchors + exploration).take(limit)
    }

    private fun rotateScoreBands(
        ranked: List<ScoredTaggedTrack>,
        random: Random
    ): List<ScoredTaggedTrack> =
        ranked
            .groupBy { (it.score * 2).toInt() }
            .toSortedMap(compareByDescending { it })
            .values
            .flatMap { band -> band.shuffled(random) }
}

data class CandidateSelection(
    val tracks: List<TaggedTrack>,
    val profile: TasteProfile,
    val desiredTags: List<String>
)

private data class ScoredTaggedTrack(val track: TaggedTrack, val score: Double)

private fun readStringIfExists(path: Path): String? =
    try {
        if (Files.exists(path)) Files.readString(path) else null
    } catch (cause: IOException) {
        org.slf4j.LoggerFactory.getLogger("fm.aftertaste.TasteProfileRepository")
            .warn("Could not read taste file {}: {}", path, cause.message)
        null
    }
