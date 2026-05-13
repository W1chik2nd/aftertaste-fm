package fm.aftertaste

import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val RUNTIME_TAG_CONFIDENCE = 0.35
private const val PREFERRED_TAG_LIMIT = 12
private const val AVOID_TAG_LIMIT = 10
private const val TOP_USE_TAG_LIMIT = 8
private const val TOP_MOOD_TAG_LIMIT = 8
private const val TOP_SOUND_TAG_LIMIT = 8
private const val LANGUAGE_COUNT_LIMIT = 12
private const val ARTIST_COUNT_LIMIT = 15
private const val TAG_COUNT_LIMIT = 14
private const val ALL_TAG_COUNT_LIMIT = 20
private const val MIN_AVOID_TAG_COUNT = 2
private const val MIN_AVOID_TAG_RISK = 0.24
private const val SKIP_RISK_WEIGHT = 0.6
private const val ENERGY_RISK_WEIGHT = 0.25
private const val INTENSITY_RISK_WEIGHT = 0.25
private const val HIGH_ENERGY_THRESHOLD = 0.7

class TasteProfileBuilder(
    private val tastePath: Path = Env.path("TASTE_DATA_DIR", "data/taste")
) {
    private val json = HttpClients.sharedJson
    private val profilePath = tastePath.resolve("profile.md")
    private val rulesPath = tastePath.resolve("rules.json")

    suspend fun write(analysis: EvidencePlaylistAnalysis) {
        val stats = ProfileStats.from(analysis.tracks)
        val preferredTags = preferredTags(stats)
        val avoidTags = avoidTags(analysis.tracks)
        val rules = TasteRules(
            preferredTags = preferredTags,
            avoidTags = avoidTags,
            moodAliases = moodAliases(stats)
        )
        AtomicFiles.writeString(profilePath, renderProfile(analysis, stats, preferredTags, avoidTags))
        AtomicFiles.writeString(rulesPath, json.encodeToString(rules) + "\n")
    }

    private fun renderProfile(
        analysis: EvidencePlaylistAnalysis,
        stats: ProfileStats,
        preferredTags: List<String>,
        avoidTags: List<String>
    ): String {
        val trackCount = analysis.tracks.size
        val generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return """
            |# Aftertaste FM Taste Profile
            |
            |Generated at: $generatedAt
            |
            |Source: ${analysis.playlistName} (${analysis.playlistId})
            |Tracks analyzed: $trackCount
            |Analysis mode: ${analysis.analysisMode}
            |
            |${runtimeSummary(analysis, preferredTags, avoidTags)}
            |${signalSummary(stats)}
            |${plannerGuidance()}
            |
        """.trimMargin()
    }

    private fun runtimeSummary(
        analysis: EvidencePlaylistAnalysis,
        preferredTags: List<String>,
        avoidTags: List<String>
    ): String {
        val trackCount = analysis.tracks.size
        val needsReview = analysis.tracks.count { it.needsReview }
        val modelEvidence = analysis.tracks.count { it.evidence.model }
        val lyricEvidence = analysis.tracks.count { it.evidence.lyrics }
        return """
            |## Runtime Summary
            |
            |This file is generated from `tracks.evidence.json` and is intended for the runtime LLM planner. It should summarize durable taste signals, not store lyrics or one-off commentary.
            |
            |- Evidence coverage: $lyricEvidence/$trackCount tracks with lyrics, $modelEvidence/$trackCount tracks with model analysis.
            |- Review queue: $needsReview/$trackCount tracks still marked `needsReview`.
            |- Default candidate tags: ${preferredTags.ifEmpty { listOf("none yet") }.joinToString(", ")}.
            |- Avoid by default: ${avoidTags.ifEmpty { listOf("none yet") }.joinToString(", ")}.
            |
        """.trimMargin()
    }

    private fun signalSummary(stats: ProfileStats): String =
        """
            |## Language Mix
            |
            |${stats.languageCounts.formatCounts("language", LANGUAGE_COUNT_LIMIT)}
            |
            |## Core Artists
            |
            |${stats.artistCounts.formatCounts("artist", ARTIST_COUNT_LIMIT)}
            |
            |## Strongest Tags
            |
            |Mood:
            |${stats.moodTags.formatCounts("tag", TAG_COUNT_LIMIT)}
            |
            |Sound:
            |${stats.soundTags.formatCounts("tag", TAG_COUNT_LIMIT)}
            |
            |Use:
            |${stats.useTags.formatCounts("tag", TAG_COUNT_LIMIT)}
            |
            |Context:
            |${stats.contextTags.formatCounts("tag", TAG_COUNT_LIMIT)}
            |
            |All tags:
            |${stats.allTags.formatCounts("tag", ALL_TAG_COUNT_LIMIT)}
            |
            |## Average Scores
            |
            |${stats.averages.entries.joinToString("\n") { "- ${it.key}: ${"%.2f".format(it.value)}" }}
            |
        """.trimMargin()

    private fun plannerGuidance(): String =
        """
            |## Planner Guidance
            |
            |- Use `tracks.evidence.json` as the source of truth for track-level selection.
            |- Prefer high-confidence tags and scores; treat low-confidence tracks as candidates for review.
            |- Keep runtime prompts compact: selected candidates, profile summary, rules, and no full lyrics.
            |- Build segments from groups of tracks. Do not make the host speak before every song.
            |- Use `avoidTags` as default friction, not a permanent ban. User intent can override it.
        """.trimMargin()

    private fun preferredTags(stats: ProfileStats): List<String> =
        (stats.useTags.topEntries(TOP_USE_TAG_LIMIT) + stats.moodTags.topEntries(TOP_MOOD_TAG_LIMIT) + stats.soundTags.topEntries(TOP_SOUND_TAG_LIMIT))
            .map { it.key }
            .filter { it.isRuntimePreferenceTag() }
            .distinct()
            .take(PREFERRED_TAG_LIMIT)

    private fun avoidTags(tracks: List<EvidenceTrackAnalysis>): List<String> {
        val stats = mutableMapOf<String, RiskStats>()
        tracks.forEach { track ->
            val risk = track.riskScore()
            track.runtimeTags().forEach { tag -> stats.getOrPut(tag) { RiskStats() }.add(risk) }
        }
        return stats.entries
            .map { AvoidTag(it.key, it.value.count, it.value.averageRisk()) }
            .filter { it.count >= MIN_AVOID_TAG_COUNT && it.risk >= MIN_AVOID_TAG_RISK }
            .sortedWith(compareByDescending<AvoidTag> { it.risk }.thenByDescending { it.count })
            .take(AVOID_TAG_LIMIT)
            .map { it.tag }
    }

    private fun moodAliases(stats: ProfileStats): Map<String, List<String>> {
        fun existing(vararg tags: String) = tags.filter { stats.hasTag(it) }
        return mapOf(
            "quiet" to existing("soft", "calm", "late-night", "acoustic", "ambient"),
            "coding" to existing("coding", "focus", "background", "low-lyric-density", "late-night"),
            "less sad" to existing("warm", "comfort", "hopeful", "bright", "romantic"),
            "rainy" to existing("rainy", "late-night", "wistful", "melancholic"),
            "commute" to existing("commute", "driving", "walking", "midtempo", "city-memory"),
            "energetic" to existing("energy-lift", "workout", "party", "edm", "dance"),
            "nostalgic" to existing("nostalgia", "nostalgia-session", "classic", "karaoke-memory", "city-memory")
        ).filterValues { it.isNotEmpty() }
    }
}

private data class ProfileStats(
    val languageCounts: Map<String, Int>,
    val artistCounts: Map<String, Int>,
    val moodTags: Map<String, Int>,
    val contextTags: Map<String, Int>,
    val soundTags: Map<String, Int>,
    val useTags: Map<String, Int>,
    val allTags: Map<String, Int>,
    val averages: Map<String, Double>
) {
    fun hasTag(tag: String): Boolean = listOf(moodTags, contextTags, soundTags, useTags).any { it.containsKey(tag) }

    companion object {
        fun from(tracks: List<EvidenceTrackAnalysis>): ProfileStats {
            val moodTags = tracks.countTags { it.moodTags }
            val contextTags = tracks.countTags { it.contextTags }
            val soundTags = tracks.countTags { it.soundTags }
            val useTags = tracks.countTags { it.useTags }
            return ProfileStats(
                languageCounts = tracks.groupingBy { it.language.value }.eachCount(),
                artistCounts = tracks.groupingBy { it.artist }.eachCount(),
                moodTags = moodTags,
                contextTags = contextTags,
                soundTags = soundTags,
                useTags = useTags,
                allTags = mergeCounts(moodTags, contextTags, soundTags, useTags),
                averages = tracks.averageScores()
            )
        }
    }
}

private data class RiskStats(var count: Int = 0, var risk: Double = 0.0) {
    fun add(value: Double) {
        count += 1
        risk += value
    }

    fun averageRisk(): Double = risk / count
}

private data class AvoidTag(val tag: String, val count: Int, val risk: Double)

private fun List<EvidenceTrackAnalysis>.countTags(selector: (EvidenceTrackAnalysis) -> List<EvidenceTag>): Map<String, Int> =
    flatMap(selector)
        .filter { it.confidence >= RUNTIME_TAG_CONFIDENCE }
        .groupingBy { it.tag }
        .eachCount()

private fun mergeCounts(vararg maps: Map<String, Int>): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    maps.forEach { map -> map.forEach { (key, value) -> counts[key] = (counts[key] ?: 0) + value } }
    return counts
}

private fun List<EvidenceTrackAnalysis>.averageScores(): Map<String, Double> {
    val scores = flatMap { track ->
        listOf(
            "energy" to track.scores.energy.value,
            "valence" to track.scores.valence.value,
            "night" to track.scores.night.value,
            "coding" to track.scores.coding.value,
            "skipRisk" to track.scores.skipRisk.value,
            "danceability" to track.scores.danceability.value,
            "acousticness" to track.scores.acousticness.value,
            "lyricDensity" to track.scores.lyricDensity.value,
            "vocalPresence" to track.scores.vocalPresence.value,
            "familiarity" to track.scores.familiarity.value,
            "intensity" to track.scores.intensity.value,
            "speechiness" to track.scores.speechiness.value,
            "instrumentalness" to track.scores.instrumentalness.value,
            "liveness" to track.scores.liveness.value,
            "emotionalIntensity" to track.scores.emotionalIntensity.value,
            "lyricalFocus" to track.scores.lyricalFocus.value,
            "mainstreamAppeal" to track.scores.mainstreamAppeal.value
        )
    }
    return scores.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.average() }
}

private fun EvidenceTrackAnalysis.runtimeTags(): List<String> =
    (moodTags + contextTags + soundTags + useTags)
        .filter { it.confidence >= RUNTIME_TAG_CONFIDENCE }
        .map { it.tag }

private fun EvidenceTrackAnalysis.riskScore(): Double {
    val energyRisk = (scores.energy.value - HIGH_ENERGY_THRESHOLD).coerceAtLeast(0.0)
    val intensityRisk = (scores.intensity.value - HIGH_ENERGY_THRESHOLD).coerceAtLeast(0.0)
    return scores.skipRisk.value * SKIP_RISK_WEIGHT + energyRisk * ENERGY_RISK_WEIGHT + intensityRisk * INTENSITY_RISK_WEIGHT
}

private fun String.isRuntimePreferenceTag(): Boolean =
    isNotBlank() && this != "unknown" && !startsWith("playlist-") && !startsWith("imported-") && !matches(Regex("^[a-z]{2}(-[A-Z]{2})?$"))

private fun Map<String, Int>.topEntries(limit: Int): List<Map.Entry<String, Int>> =
    entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit)

private fun Map<String, Int>.formatCounts(label: String, limit: Int): String =
    topEntries(limit).takeIf { it.isNotEmpty() }?.joinToString("\n") { "- ${it.key}: ${it.value}" } ?: "- no $label signals yet"
