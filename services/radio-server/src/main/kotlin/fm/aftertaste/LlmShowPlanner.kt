package fm.aftertaste

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_LLM_SEGMENTS = 4
private const val DEFAULT_LLM_CANDIDATE_LIMIT = 30
private const val MIN_LLM_CANDIDATE_LIMIT = 12
private const val MAX_LLM_CANDIDATE_LIMIT = 48
private const val CANDIDATE_TAG_LIMIT = 12
private const val CANDIDATE_NOTE_CHARS = 180
private const val TASTE_PROFILE_LINE_LIMIT = 36
private const val LLM_PLAN_MAX_TOKENS = 1400

interface LlmShowPlanner {
    val mode: String

    suspend fun plan(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile? = null,
        taggedCandidates: List<TaggedTrack> = emptyList()
    ): LlmShowPlan?
}

class DisabledLlmShowPlanner : LlmShowPlanner {
    override val mode: String = "local-fallback"

    override suspend fun plan(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): LlmShowPlan? = null
}

class ConfiguredLlmShowPlanner(
    private val config: LlmRuntimeConfig
) : LlmShowPlanner {
    override val mode: String = "llm-radio-agent:${config.displayName}"

    private val json: Json = HttpClients.sharedJson
    private val completionClient = LlmCompletionClient(config, json)

    override suspend fun plan(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): LlmShowPlan? {
        if (candidates.size < SEGMENT_TRACK_COUNT) return null
        val outputText = completionClient.complete(
            systemPrompt(hostConfig),
            userPrompt(context, hostConfig, candidates, tasteProfile, taggedCandidates),
            maxTokens = LLM_PLAN_MAX_TOKENS,
            jsonMode = true,
            responseSchema = responseFormatSchema(),
            cacheSystemPrompt = true
        ) ?: throw UpstreamApiException("LLM upstream did not return output text.")
        return json.decodeFromString<LlmShowPlan>(outputText).normalize(candidates)
            ?: throw UpstreamApiException("LLM show plan did not contain enough valid segments.")
    }

    private fun systemPrompt(hostConfig: HostConfig): String =
        """
        You are Aftertaste, a restrained private radio director.
        Create a radio show from ONLY the provided candidate track ids.
        Each segment is a chapter. The first trackId is the chapter lead: the host will speak over that track's opening, then the rest of the chapter plays without interruption.
        Do not introduce every song. Talk once per chapter, not before every track.
        Host language must be ${hostConfig.hostLanguage}. For v0.1, write natural English even when tracks are Chinese.
        Style: ${hostConfig.hostStyle}. Specific, companionable, never oily, never encyclopedic.
        Host scripts should feel like a real radio break for the current station mode, not product copy.

        Structure each hostScript like this:
        1. Vary the opening across chapters. Do not start every script with the time, the city, or the same sentence shape.
        2. Chapter One may mention time or weather once, using a friendly place name like "Leeds", not a geocoding string like "Leeds, England, GB".
        3. Later chapters should respond to what has already played: continuation, turn, lift, or closing.
        4. Name the emotional thread of the previous or coming songs in plain language.
        5. Land on the lead track and artist as the next thing we are hearing.
        6. If evidence supports it, briefly describe what the song seems to carry. If not, speak from mood and listening context rather than facts.
        7. End naturally with "Now, [artist], [title]."

        Across the hostScripts, avoid repeating the same first 8 words, the same paragraph structure, or the same metaphors.
        Never use the sentence "The city always seems to get a little more honest around this hour" or close variants.
        Avoid phrases like "chosen because", "anchor song", "the shape of your request", "candidate", "segment", "playlist logic", "emotional arc", or "this next run".
        Do not invent factual biography, release history, recording details, or lyric meaning unless the candidate notes clearly support it.
        Each hostScript should feel like 35-60 seconds of radio copy.
        Return only JSON matching this shape: {"title":"...","rationale":"...","segments":[{"title":"Chapter One - ...","hostScript":"...","trackIds":["id1","id2","id3"]}]}.
        """.trimIndent()

    private fun userPrompt(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): String {
        val tagsById = taggedCandidates.associateBy { it.id }
        val candidateLimit = Env.value("LLM_CANDIDATE_LIMIT")
            ?.toIntOrNull()
            ?.coerceIn(MIN_LLM_CANDIDATE_LIMIT, MAX_LLM_CANDIDATE_LIMIT)
            ?: DEFAULT_LLM_CANDIDATE_LIMIT
        val candidateText = candidates.take(candidateLimit).joinToString("\n") { track ->
            val tagged = tagsById[track.id]
            val tasteText = tagged?.let {
                "; language=${it.language}; tags=${it.tags.take(CANDIDATE_TAG_LIMIT).joinToString("|")}; energy=${it.energy}; valence=${it.valence}; night=${it.nightScore}; coding=${it.codingScore}; skipRisk=${it.skipRisk}; notes=${it.notes?.take(CANDIDATE_NOTE_CHARS) ?: "none"}"
            } ?: ""
            "- id=${track.id}; title=${track.title}; artist=${track.artist}; album=${track.album ?: "unknown"}; durationMs=${track.durationMs ?: "unknown"}; provider=${track.provider}; stream=${if (track.streamUrl == null) "unavailable" else "available"}$tasteText"
        }
        val tasteProfileText = tasteProfile?.profileText
            ?.lines()
            ?.take(TASTE_PROFILE_LINE_LIMIT)
            ?.joinToString("\n")
            ?: "No local profile text loaded."

        return """
        User request: ${context.mood ?: "Generate today's show."}
        Intent: ${context.intent}
        Routing intent: ${routingSummary(context.routing)}
        Station mode: ${context.stationStyle?.label ?: "Default"}; style=${context.stationStyle?.hostStyle ?: hostConfig.hostStyle}
        Recent context: ${memorySummary(context.memory)}
        Variation seed: ${context.variationSeed ?: "none"}
        Local time: ${context.localTime}
        Weather context: ${context.weather?.let { "${friendlyPlaceName(it.locationName)}, ${it.condition}, ${it.temperatureC}C, feels ${it.apparentTemperatureC ?: it.temperatureC}C" } ?: "none"}
        Catalog constraint: ${catalogConstraint(context.routing)}
        Artist constraint: ${artistConstraint(context.routing)}

        Taste profile:
        $tasteProfileText

        Candidate tracks:
        $candidateText

        Build $MAX_LLM_SEGMENTS segments. Each segment should contain exactly $SEGMENT_TRACK_COUNT trackIds when possible.
        Use ${MAX_LLM_SEGMENTS * SEGMENT_TRACK_COUNT} unique trackIds when possible. Do not repeat the same track in different chapters.
        Title segments as chapters, e.g. "Chapter One - ...".
        Put a strong lead song first in each chapter so it can carry the host voice-over.
        Keep the track flow emotionally coherent, but let the variation seed change the exact picks and order across repeated runs.
        If an artist constraint is present, the requested artist is allowed to repeat; still avoid selecting duplicate versions of the same title when alternatives exist.
        """.trimIndent()
    }

    private fun responseFormatSchema(): JsonObject = buildJsonObject {
        put("type", "json_schema")
        put("name", "aftertaste_show_plan")
        put("strict", true)
        put("schema", buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", buildJsonArray {
                add(JsonPrimitive("title"))
                add(JsonPrimitive("rationale"))
                add(JsonPrimitive("segments"))
            })
            put("properties", buildJsonObject {
                put("title", stringSchema())
                put("rationale", stringSchema())
                put("segments", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("required", buildJsonArray {
                            add(JsonPrimitive("title"))
                            add(JsonPrimitive("hostScript"))
                            add(JsonPrimitive("trackIds"))
                        })
                        put("properties", buildJsonObject {
                            put("title", stringSchema())
                            put("hostScript", stringSchema())
                            put("trackIds", buildJsonObject {
                                put("type", "array")
                                put("items", stringSchema())
                            })
                        })
                    })
                })
            })
        })
    }

    /**
     * Trim and validate the LLM output. We do NOT pad short segments with arbitrary fallback ids any more —
     * if the model returns a segment with too few valid ids, we drop the segment instead of pretending the
     * model picked the leftover tracks. This keeps the agent honest.
     */
    private fun LlmShowPlan.normalize(candidates: List<Track>): LlmShowPlan? {
        val validIds = candidates.map { it.id }.toSet()
        val usedIds = linkedSetOf<String>()
        val cleanedSegments = segments.mapNotNull { segment ->
            val ids = segment.trackIds
                .filter { it in validIds && it !in usedIds }
                .distinct()
                .take(SEGMENT_TRACK_COUNT)
            if (ids.size < SEGMENT_TRACK_COUNT) {
                null
            } else {
                usedIds += ids
                segment.copy(trackIds = ids)
            }
        }.take(MAX_LLM_SEGMENTS)
        if (cleanedSegments.size < MIN_SHOW_SEGMENTS) return null
        return copy(
            title = title.ifBlank { "Aftertaste Session" },
            rationale = rationale,
            segments = cleanedSegments
        )
    }

    companion object {
        fun fromEnvironment(): LlmShowPlanner =
            LlmRuntimeConfig.fromEnvironment(LlmUseCase.Planner)
                ?.let { ConfiguredLlmShowPlanner(it) }
                ?: DisabledLlmShowPlanner()
    }
}

private fun memorySummary(memory: ContextMemory): String {
    if (memory.isEmpty()) return "none"
    val parts = buildList {
        memory.recentPlans.takeIf { it.isNotEmpty() }?.let {
            add("recent plans: ${it.joinToString(" | ")}")
        }
        memory.recentPlays.takeIf { it.isNotEmpty() }?.let { plays ->
            add(plays.joinToString(" | ") { "${it.action} ${it.title} by ${it.artist ?: "unknown"}" })
        }
        memory.recentMessages.takeIf { it.isNotEmpty() }?.let { msgs ->
            add(msgs.joinToString(" | ") { "${it.role}: ${it.content}" })
        }
    }
    return parts.joinToString("; ")
}

private fun routingSummary(routing: RoutingIntent): String {
    if (routing.isEmpty()) return "none"
    return listOfNotNull(
        routing.language?.let { "language=$it" },
        routing.energy?.let { "energy=$it" },
        routing.routine?.let { "routine=$it" },
        routing.moodTag?.let { "mood=$it" },
        routing.avoid.takeIf { it.isNotEmpty() }?.let { "avoid=${it.joinToString("|")}" },
        routing.artists.takeIf { it.isNotEmpty() }?.let { "artists=${it.joinToString("|")}" }
    ).joinToString(", ")
}

private fun catalogConstraint(routing: RoutingIntent): String =
    when (routing.language) {
        "en" -> "Use only English-language tracks from the candidates."
        "zh-CN" -> "Use Mandarin / zh-CN tracks from the candidates."
        "zh" -> "Prefer Chinese-language tracks from the candidates."
        "yue" -> "Prefer Cantonese tracks from the candidates."
        null -> "No hard language constraint."
        else -> "Prefer ${routing.language} tracks from the candidates."
    }

private fun artistConstraint(routing: RoutingIntent): String =
    if (routing.artists.isEmpty()) {
        "No hard artist constraint."
    } else {
        "Hard preference: use ${routing.artists.joinToString(", ")} tracks as the main catalog. " +
            "If enough candidate tracks by this artist are present, selected tracks should mainly be by this artist, " +
            "but duplicate titles should still be avoided."
    }
