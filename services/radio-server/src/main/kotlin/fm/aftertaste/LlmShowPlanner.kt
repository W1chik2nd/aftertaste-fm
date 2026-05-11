package fm.aftertaste

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface LlmShowPlanner {
    suspend fun plan(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile? = null,
        taggedCandidates: List<TaggedTrack> = emptyList()
    ): LlmShowPlan?
}

class DisabledLlmShowPlanner : LlmShowPlanner {
    override suspend fun plan(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): LlmShowPlan? = null
}

class OpenAiLlmShowPlanner(
    private val apiKey: String,
    private val model: String
) : LlmShowPlanner {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    override suspend fun plan(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): LlmShowPlan? {
        if (candidates.size < 3) return null
        return runCatching {
            val responseText = client.post("https://api.openai.com/v1/responses") {
                bearerAuth(apiKey)
                header("OpenAI-Beta", "responses=v1")
                contentType(ContentType.Application.Json)
                setBody(requestBody(context, hostConfig, candidates, tasteProfile, taggedCandidates))
            }.body<String>()

            val outputText = extractOutputText(json.parseToJsonElement(responseText)) ?: return null
            json.decodeFromString<LlmShowPlan>(outputText).normalize(candidates)
        }.getOrNull()
    }

    private fun requestBody(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): JsonObject {
        val tagsById = taggedCandidates.associateBy { it.id }
        val candidateLimit = Env.value("LLM_CANDIDATE_LIMIT")?.toIntOrNull()?.coerceIn(12, 48) ?: 30
        val candidateText = candidates.take(candidateLimit).joinToString("\n") { track ->
            val tagged = tagsById[track.id]
            val tasteText = tagged?.let {
                "; language=${it.language}; tags=${it.tags.take(12).joinToString("|")}; energy=${it.energy}; valence=${it.valence}; night=${it.nightScore}; coding=${it.codingScore}; skipRisk=${it.skipRisk}; notes=${it.notes?.take(180) ?: "none"}"
            } ?: ""
            "- id=${track.id}; title=${track.title}; artist=${track.artist}; album=${track.album ?: "unknown"}; durationMs=${track.durationMs ?: "unknown"}; provider=${track.provider}; stream=${if (track.streamUrl == null) "unavailable" else "available"}$tasteText"
        }
        val tasteProfileText = tasteProfile?.profileText
            ?.lines()
            ?.take(36)
            ?.joinToString("\n")
            ?: "No local profile text loaded."

        return buildJsonObject {
            put("model", model)
            put("input", buildJsonArray {
                add(buildMessage(
                    role = "system",
                    content = """
                    You are Aftertaste, a restrained private late-night radio director.
                    Create a radio show from ONLY the provided candidate track ids.
                    Each segment is a chapter. The first trackId is the chapter lead: the host will speak over that track's opening, then the rest of the chapter plays without interruption.
                    Do not introduce every song. Talk once per chapter, not before every track.
                    Host language must be ${hostConfig.hostLanguage}. For v0.1, write natural English even when tracks are Chinese.
                    Style: ${hostConfig.hostStyle}. Calm, specific, companionable, never oily, never encyclopedic.
                    Host scripts should feel like a real late-night radio break, not product copy.
                    Structure each hostScript like this:
                    1. Vary the opening across chapters. Do not start every script with the time, the city, or the same sentence shape.
                    2. Chapter One may establish the room/time/weather. Later chapters should respond to what has already played: continuation, turn, lift, or closing.
                    3. Name the emotional thread of the previous or coming songs in plain language.
                    4. Land on the lead track and artist as the next thing we are hearing.
                    5. If evidence supports it, briefly describe what the song seems to carry. If not, speak from mood and listening context rather than facts.
                    6. End naturally with "Now, [artist], [title]."
                    Across the four hostScripts, avoid repeating the same first 8 words, the same paragraph structure, or the same metaphors.
                    Avoid phrases like "chosen because", "anchor song", "the shape of your request", "candidate", "segment", "playlist logic", "emotional arc", or "this next run".
                    Do not invent factual biography, release history, recording details, or lyric meaning unless the candidate notes clearly support it.
                    Each hostScript should feel like 35-60 seconds of radio copy.
                    Return JSON that exactly matches the schema.
                    """.trimIndent()
                ))
                add(buildMessage(
                    role = "user",
                    content = """
                    User request: ${context.mood ?: "Generate today's show."}
                    Intent: ${context.intent}
                    Context signals: ${context.recentSignals.joinToString(", ")}
                    Local time: ${context.localTime}
                    Weather context: ${context.weather?.let { "${it.locationName}, ${it.condition}, ${it.temperatureC}C, feels ${it.apparentTemperatureC ?: it.temperatureC}C" } ?: "none"}
                    Catalog constraint: ${catalogConstraint(context)}
                    Artist constraint: ${artistConstraint(context)}

                    Taste profile:
                    $tasteProfileText

                    Candidate tracks:
                    $candidateText

                    Build 4 segments. Each segment should contain exactly 3 trackIds when possible.
                    Title segments as chapters, e.g. "Chapter One - ...".
                    Put the strongest anchor song first in each segment so it can carry the host voice-over.
                    Use 12 unique tracks when possible. Keep the track flow emotionally coherent.
                    If an artist constraint is present, the requested artist is allowed to repeat; do not dilute the request with unrelated artists when enough requested-artist tracks exist.
                    """.trimIndent()
                ))
            })
            put("text", buildJsonObject {
                put("format", responseFormatSchema())
            })
            put("max_output_tokens", 1400)
        }
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

    private fun LlmShowPlan.normalize(candidates: List<Track>): LlmShowPlan? {
        val validIds = candidates.map { it.id }.toSet()
        val cleanedSegments = segments.mapNotNull { segment ->
            val ids = segment.trackIds.filter { it in validIds }.distinct().take(3)
            if (ids.size < 3) null else segment.copy(trackIds = ids)
        }.take(4)
        if (cleanedSegments.size < 2) return null
        return copy(
            title = title.take(80).ifBlank { "Aftertaste Session" },
            rationale = rationale.take(400),
            segments = cleanedSegments
        )
    }

    private fun extractOutputText(element: JsonElement): String? {
        if (element is JsonObject) {
            element["output_text"]?.jsonPrimitive?.contentOrNull?.let { return it }
            if (element["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                element["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
            }
            element.values.forEach { child ->
                extractOutputText(child)?.let { return it }
            }
        }
        if (element is JsonArray) {
            element.forEach { child ->
                extractOutputText(child)?.let { return it }
            }
        }
        return null
    }

    companion object {
        fun fromEnvironment(): LlmShowPlanner {
            val apiKey = Env.value("OPENAI_API_KEY") ?: return DisabledLlmShowPlanner()
            val model = Env.value("OPENAI_MODEL") ?: "gpt-5.2"
            return OpenAiLlmShowPlanner(apiKey, model)
        }
    }
}

class AgentChatService(
    private val apiKey: String?,
    private val model: String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun reply(message: String, playback: PlaybackState, hostConfig: HostConfig): AgentChatResponse {
        if (apiKey.isNullOrBlank()) {
            return AgentChatResponse(
                message = fallbackReply(message, playback),
                mode = "local-fallback"
            )
        }
        val authToken = apiKey.orEmpty()

        return runCatching {
            val responseText = client.post("https://api.openai.com/v1/responses") {
                bearerAuth(authToken)
                header("OpenAI-Beta", "responses=v1")
                contentType(ContentType.Application.Json)
                setBody(chatRequestBody(message, playback, hostConfig))
            }.body<String>()
            val output = extractPlainText(json.parseToJsonElement(responseText))
                ?.trim()
                ?.take(900)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackReply(message, playback)
            AgentChatResponse(message = output, mode = "openai-chat")
        }.getOrElse {
            AgentChatResponse(message = fallbackReply(message, playback), mode = "local-fallback")
        }
    }

    private fun chatRequestBody(message: String, playback: PlaybackState, hostConfig: HostConfig): JsonObject =
        buildJsonObject {
            put("model", model)
            put("input", buildJsonArray {
                add(buildMessage(
                    role = "system",
                    content = """
                    You are Aftertaste, the private AI radio host inside a music player.
                    Reply to ordinary user chat in ${hostConfig.hostLanguage}, usually in 1-3 short sentences.
                    Be useful and warm, but do not generate a new playlist unless the user explicitly asks to change music, mood, language, energy, or show direction.
                    If asked what you can do, explain that you can tune the station, skip/pause/resume playback, discuss the current track, and build hosted radio chapters.
                    Avoid product-planning jargon and avoid claiming facts about a song unless they are present in the current playback context.
                    """.trimIndent()
                ))
                add(buildMessage(
                    role = "user",
                    content = """
                    Current show: ${playback.showTitle ?: "none"}
                    Current item type: ${playback.currentItem?.type ?: "none"}
                    Current segment: ${playback.segmentTitle ?: "none"}
                    Current track: ${playback.currentItem?.track?.title ?: "none"} by ${playback.currentItem?.track?.artist ?: "unknown"}
                    User message: $message
                    """.trimIndent()
                ))
            })
            put("max_output_tokens", 220)
        }

    private fun fallbackReply(message: String, playback: PlaybackState): String {
        val lower = message.lowercase()
        return when {
            "help" in lower || "what can you" in lower ->
                "I can tune the station by mood, language, energy, or scene; skip, pause, resume, or go back; and talk about what is playing. Try something like “more English and less sad” or “quiet songs for late-night coding.”"
            playback.currentItem != null ->
                "I am here with the current chapter. Tell me if you want the music softer, brighter, more English, less sad, or just ask about what is playing."
            else ->
                "Tell me what the room needs, and I can build a hosted radio chapter around it."
        }
    }

    companion object {
        fun fromEnvironment(): AgentChatService {
            val apiKey = Env.value("OPENAI_API_KEY")
            val model = Env.value("OPENAI_CHAT_MODEL") ?: Env.value("OPENAI_MODEL") ?: "gpt-5.2"
            return AgentChatService(apiKey, model)
        }
    }
}

private fun catalogConstraint(context: RecommendationContext): String =
    when {
        context.recentSignals.any { it == "catalog=english-only" } -> "Use only English-language tracks from the candidates."
        context.recentSignals.any { it == "catalog=mandarin" } -> "Use Mandarin / zh-CN tracks from the candidates."
        context.recentSignals.any { it == "catalog=chinese-indie-ok" } -> "Prefer Chinese-language tracks from the candidates."
        else -> "No hard language constraint."
    }

private fun artistConstraint(context: RecommendationContext): String {
    val artists = context.recentSignals
        .filter { it.startsWith("artist=") }
        .map { it.removePrefix("artist=") }
        .filter { it.isNotBlank() }
    return if (artists.isEmpty()) {
        "No hard artist constraint."
    } else {
        "Hard preference: use ${artists.joinToString(", ")} tracks as the main catalog. If enough candidate tracks by this artist are present, every selected track should be by this artist."
    }
}

private fun buildMessage(role: String, content: String): JsonObject =
    buildJsonObject {
        put("role", role)
        put("content", content)
    }

private fun stringSchema(): JsonObject = buildJsonObject {
    put("type", "string")
}

private fun extractPlainText(element: JsonElement): String? {
    if (element is JsonObject) {
        element["output_text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        if (element["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
            element["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        element.values.forEach { child ->
            extractPlainText(child)?.let { return it }
        }
    }
    if (element is JsonArray) {
        element.forEach { child ->
            extractPlainText(child)?.let { return it }
        }
    }
    return null
}
