package fm.aftertaste

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.random.Random

class TasteProfileRepository(
    private val tastePath: Path = Path.of("../../data/taste"),
    private val examplePath: Path = Path.of("../../data/taste.example")
) {
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
            ?.let { content -> runCatching { json.decodeFromString<TasteRules>(content) }.getOrNull() }
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
                runCatching {
                    json.decodeFromString<EvidencePlaylistAnalysis>(content)
                        .tracks
                        .filterNot {
                            it.needsReview && it.evidence.lyrics.not() && it.evidence.manual.not() && it.evidence.model.not()
                        }
                        .map { it.toTaggedTrack() }
                }.getOrNull()
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        readStringIfExists(root.resolve("tracks.json"))
            ?.let { content ->
                runCatching { json.decodeFromString(ListSerializer(TaggedTrack.serializer()), content) }.getOrNull()
            }
            ?.let { return it }

        return emptyList()
    }
}

class CandidateSelector(private val repository: TasteProfileRepository) {
    fun select(context: RecommendationContext): CandidateSelection {
        val profile = repository.load()
        if (profile.tracks.isEmpty()) {
            return CandidateSelection(emptyList(), profile, emptyList())
        }

        val desiredTags = desiredTags(context, profile.rules)
        val prompt = context.mood.orEmpty().lowercase()
        val scopedTracks = scopedTracks(profile.tracks, context, profile.rules)
            .distinctBy { "${normalizeForMatch(it.artist)}|${normalizeForMatch(it.title)}" }
        val scored = scopedTracks.map { tagged ->
            ScoredTaggedTrack(tagged, score(tagged, desiredTags, prompt, profile.rules))
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

    private fun desiredTags(context: RecommendationContext, rules: TasteRules): List<String> {
        val prompt = context.mood.orEmpty().lowercase()
        val tags = linkedSetOf<String>()
        context.recentSignals.forEach { signal ->
            when {
                "routine=late-night-coding" in signal -> tags += listOf("coding", "focus", "late-night")
                "energy=low" in signal -> tags += listOf("quiet", "soft", "low-energy")
                "catalog=english-only" in signal -> tags += listOf("en", "english", "western-pop")
                "catalog=chinese-indie-ok" in signal -> tags += listOf("chinese-indie", "zh-CN", "indie")
                "catalog=mandarin" in signal -> tags += listOf("zh-CN", "mandarin", "国语")
                signal.startsWith("artist=") -> tags += signal.removePrefix("artist=")
                "avoid=too-sad" in signal -> tags += listOf("not-too-sad", "warm", "soft")
            }
        }
        rules.moodAliases.forEach { (alias, aliasTags) ->
            if (alias.lowercase() in prompt) tags += aliasTags
        }
        if ("rain" in prompt) tags += "rain"
        if ("commute" in prompt) tags += "commute"
        if (tags.isEmpty()) tags += rules.preferredTags
        return tags.toList()
    }

    private fun score(track: TaggedTrack, desiredTags: List<String>, prompt: String, rules: TasteRules): Double {
        val normalizedTags = track.tags.map { it.lowercase() }.toSet()
        val desired = desiredTags.map { it.lowercase() }.toSet()
        val tagScore = desired.count { it in normalizedTags || it == track.language.lowercase() } * 1.25
        val avoidPenalty = rules.avoidTags.count { it.lowercase() in normalizedTags } * 1.4
        val languageScore = when {
            ("chinese" in prompt || "中文" in prompt) && track.language.startsWith("zh") -> 1.8
            ("english" in prompt || "英文" in prompt) && track.language == "en" -> 1.2
            else -> 0.0
        }
        val energyTarget = when {
            "not too energetic" in prompt || "quiet" in prompt || "soft" in prompt -> 0.28
            "energetic" in prompt -> 0.68
            else -> 0.4
        }
        val energyFit = 1.0 - abs(track.energy - energyTarget)
        val codingBoost = if ("coding" in prompt) track.codingScore else 0.0
        val nightBoost = track.nightScore * 0.7
        val valenceBoost = if ("less sad" in prompt) track.valence * 0.8 else 0.0
        return tagScore + languageScore + energyFit + codingBoost + nightBoost + valenceBoost - track.skipRisk - avoidPenalty
    }

    private fun scopedTracks(
        tracks: List<TaggedTrack>,
        context: RecommendationContext,
        rules: TasteRules
    ): List<TaggedTrack> {
        val signals = context.recentSignals.joinToString(" ")
        val prompt = context.mood.orEmpty().lowercase()
        val artistScoped = requestedArtistTracks(tracks, context, rules)
        val baseTracks = artistScoped.takeIf { it.size >= 3 } ?: tracks

        if ("catalog=english-only" in signals || "english" in prompt || "英文" in prompt) {
            val englishTracks = baseTracks.filter { it.language == "en" }
            if (englishTracks.size >= 6) return englishTracks
            if (artistScoped.size >= 3) return artistScoped
        }
        if ("catalog=mandarin" in signals || "mandarin" in prompt || "国语" in prompt || "普通话" in prompt) {
            val mandarinTracks = baseTracks.filter { it.language == "zh-CN" || it.language == "zh" }
            if (mandarinTracks.size >= 6) return mandarinTracks
            if (artistScoped.size >= 3) return artistScoped
        }
        if ("catalog=chinese-indie-ok" in signals || "chinese" in prompt || "中文" in prompt) {
            val chineseTracks = baseTracks.filter { it.language.startsWith("zh") || it.language == "yue" }
            if (chineseTracks.size >= 6) return chineseTracks
            if (artistScoped.size >= 3) return artistScoped
        }
        return baseTracks
    }

    private fun requestedArtistTracks(
        tracks: List<TaggedTrack>,
        context: RecommendationContext,
        rules: TasteRules
    ): List<TaggedTrack> {
        val prompt = context.mood.orEmpty()
        val promptKey = normalizeForMatch(prompt)
        val explicitArtists = context.recentSignals
            .filter { it.startsWith("artist=") }
            .map { it.removePrefix("artist=").trim() }
        val aliasArtists = rules.artistAliases
            .filter { (alias, _) -> alias in promptKey }
            .flatMap { it.value }
        val catalogArtists = tracks
            .flatMap { splitArtists(it.artist) }
            .distinct()
            .filter { artist ->
                val key = normalizeForMatch(artist)
                key.length >= 3 && key in promptKey
            }

        val requested = (explicitArtists + aliasArtists + catalogArtists)
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (requested.isEmpty()) return emptyList()

        return tracks.filter { track ->
            val artistKey = normalizeForMatch(track.artist)
            requested.any { requestedArtist -> requestedArtist in artistKey || artistKey in requestedArtist }
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

private fun splitArtists(artist: String): List<String> =
    artist.split(",", "，", "&", "、", " feat. ", " ft. ", " and ")
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun normalizeForMatch(value: String): String =
    value.lowercase()
        .replace(Regex("""[\s.'’\-_/()（）·]+"""), "")

private fun readStringIfExists(path: Path): String? =
    if (Files.exists(path)) Files.readString(path) else null
