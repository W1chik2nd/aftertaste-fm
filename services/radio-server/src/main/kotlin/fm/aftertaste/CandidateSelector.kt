package fm.aftertaste

import kotlin.math.abs
import kotlin.random.Random

class CandidateSelector(private val repository: TasteProfileRepository) {
    fun select(context: RecommendationContext, profile: TasteProfile = repository.load()): CandidateSelection {
        if (profile.tracks.isEmpty()) {
            return CandidateSelection(emptyList(), profile, emptyList())
        }

        val routing = context.routing
        val station = context.stationStyle ?: stationStyleFor(java.time.OffsetDateTime.now())
        val desiredTags = desiredTags(routing, profile.rules)
        val scopedTracks = scopedTracks(profile.tracks, routing)
            .distinctBy { "${normalizeForMatch(it.artist)}|${normalizeForMatch(it.title)}" }
        val scored = scopedTracks.map { tagged ->
            ScoredTaggedTrack(tagged, score(tagged, desiredTags, routing, profile.rules, station))
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
        rules: TasteRules,
        station: StationStyle
    ): Double {
        val normalizedTags = track.tags.map { it.lowercase() }.toSet()
        val desired = desiredTags.map { it.lowercase() }.toSet()
        val tagScore = desired.count { tagMatches(normalizedTags, it) || it == track.language.lowercase() } * 1.25
        val avoidPenalty = rules.avoidTags.count { tagMatches(normalizedTags, it.lowercase()) } * 1.4
        val languageScore = when (routing.language) {
            "zh", "zh-CN" -> if (track.language.startsWith("zh")) 1.8 else 0.0
            "en" -> if (track.language == "en") 1.2 else 0.0
            "yue" -> if (track.language == "yue") 1.6 else 0.0
            else -> 0.0
        }
        val energyTarget = when (routing.energy) {
            "low" -> 0.28
            "high" -> 0.68
            else -> station.energyTarget
        }
        val energyFit = 1.0 - abs(track.energy - energyTarget)
        val codingBoost = if (routing.routine == "late-night-coding") track.codingScore else 0.0
        val nightBoost = track.nightScore * station.nightWeight
        val valenceBoost = when (routing.moodTag) {
            "less-sad" -> track.valence * 0.8
            else -> track.valence * station.valenceWeight
        }
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
        val tagScoped = tagScopedTracks(baseTracks, routing).takeIf { it.size >= 6 }
        if (tagScoped != null) return tagScoped

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

    private fun tagScopedTracks(tracks: List<TaggedTrack>, routing: RoutingIntent): List<TaggedTrack> {
        val desired = routing.extraTags.map { it.lowercase() }.toSet()
        if (desired.isEmpty()) return emptyList()
        return tracks.filter { track ->
            track.tags.any { tag -> desired.any { tagMatches(tag.lowercase(), it) } }
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
        val anchors = ranked.take(anchorWindow).shuffled(random).take(anchorCount)
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

private fun tagMatches(tags: Set<String>, desired: String): Boolean =
    tags.any { tagMatches(it, desired) }

private fun tagMatches(tag: String, desired: String): Boolean =
    tag == desired || tag.split("-").any { it == desired }
