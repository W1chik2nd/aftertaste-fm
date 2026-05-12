package fm.aftertaste

import java.time.OffsetDateTime
import kotlin.random.Random

class RadioAgent {
    fun buildContext(
        mood: String?,
        hostConfig: HostConfig,
        tasteRules: TasteRules = TasteRules(),
        catalogArtists: List<String> = emptyList()
    ): RecommendationContext {
        val cleanedMood = mood?.trim()?.takeIf { it.isNotBlank() }
        val routing = IntentExtractor.extract(cleanedMood, tasteRules, catalogArtists)
        val intent = inferIntent(cleanedMood, routing)
        val signals = buildSignals(hostConfig, routing)
        val seed = "${cleanedMood ?: "daily"}-${OffsetDateTime.now().toEpochSecond()}-${Random.nextInt(0, 100_000)}"
        return RecommendationContext(
            mood = cleanedMood,
            localTime = OffsetDateTime.now().toString(),
            hostLanguage = hostConfig.hostLanguage,
            intent = intent,
            routing = routing,
            recentSignals = signals,
            variationSeed = seed
        )
    }

    fun trace(
        context: RecommendationContext,
        tracks: List<Track>,
        plan: ShowPlan,
        plannerMode: String,
        rationale: String? = null,
        candidateSelection: CandidateSelection? = null
    ): AgentTrace {
        val trackLanguages = tracks.map { track ->
            if (track.title.any { it.code > 127 } || track.artist.any { it.code > 127 }) "mixed/catalog-cn" else "latin-catalog"
        }.distinct()
        val routingPath = describeRoutingPath(candidateSelection, plannerMode)
        return AgentTrace(
            mode = plannerMode,
            summary = rationale?.takeIf { it.isNotBlank() }
                ?: "I read the request as ${context.intent.replace("_", " ")} and built ${plan.segments.size} segments, keeping the host between song groups instead of interrupting every track.",
            contextWindow = listOfNotNull(
                candidateSelection?.let { "taste source: ${it.profile.source}" } ?: "taste source: provider fallback",
                candidateSelection?.let { "matched tags: ${it.desiredTags.joinToString(", ").ifBlank { "none" }}" },
                "routine: ${context.routing.routine ?: context.mood ?: "daily late-night listening"}",
                "language hint: ${context.routing.language ?: "none"}",
                context.routing.artists.takeIf { it.isNotEmpty() }?.let { "artist hint: ${it.joinToString(", ")}" },
                "time: ${context.localTime ?: "now"}",
                context.weather?.let { "weather: ${it.locationName}, ${it.condition}, ${"%.0f".format(it.temperatureC)}C" },
                "host: ${context.hostLanguage}, ${plan.hostConfig.hostStyle}",
                "provider candidates: ${tracks.size} normalized tracks"
            ),
            routing = routingPath,
            recommendationStrategy = listOf(
                "Use offline track tags as the first candidate source.",
                "Prefer low-to-medium energy continuity.",
                "Treat the first track in each segment as the chapter lead.",
                "Speak over the lead track opening, then let the remaining tracks run without interruption.",
                "Use English host copy even when the tracks are Chinese or mixed.",
                "Return unavailable stream reasons instead of crashing."
            ),
            signals = listOf(
                AgentSignal("intent", context.intent),
                AgentSignal("mood", context.mood ?: "daily"),
                AgentSignal("catalog", trackLanguages.joinToString(", ")),
                AgentSignal("queue", "${plan.segments.size} host breaks / ${tracks.size} tracks")
            ) + context.recentSignals.mapIndexed { index, value -> AgentSignal("signal ${index + 1}", value) }
        )
    }

    private fun inferIntent(mood: String?, routing: RoutingIntent): String =
        when {
            mood == null -> "daily_show"
            "less-sad" == routing.moodTag -> "retune_mood"
            routing.artists.isNotEmpty() -> "artist_request"
            routing.energy != null || routing.routine != null || routing.language != null -> "mood_request"
            else -> "conversation_tuning"
        }

    private fun buildSignals(hostConfig: HostConfig, routing: RoutingIntent): List<String> = buildList {
        add("host=${hostConfig.hostName}")
        add("language=${hostConfig.hostLanguage}")
        add("speech=${hostConfig.segmentSpeechMode}")
        routing.routine?.let { add("routine=$it") }
        routing.energy?.let { add("energy=$it") }
        routing.language?.let { add("language-hint=$it") }
        routing.moodTag?.let { add("mood-tag=$it") }
        routing.avoid.forEach { add("avoid=$it") }
        routing.artists.forEach { add("artist=$it") }
    }

    /**
     * Real routing path based on what actually happened, not a hardcoded story.
     */
    private fun describeRoutingPath(candidateSelection: CandidateSelection?, plannerMode: String): List<String> {
        val path = mutableListOf<String>()
        path += "chat/request -> RadioAgent"
        if (candidateSelection != null && candidateSelection.tracks.isNotEmpty()) {
            path += "RadioAgent -> TasteProfileRepository(${candidateSelection.profile.source})"
            path += "TasteProfileRepository -> CandidateSelector(${candidateSelection.tracks.size} tracks)"
        } else {
            path += "RadioAgent -> MusicProvider.getRecommendations"
        }
        path += if (plannerMode.startsWith("llm-")) {
            "candidates -> LlmShowPlanner($plannerMode)"
        } else {
            "candidates -> ShowPlanner (deterministic fallback)"
        }
        path += "segments -> PlaybackQueue"
        return path
    }
}
