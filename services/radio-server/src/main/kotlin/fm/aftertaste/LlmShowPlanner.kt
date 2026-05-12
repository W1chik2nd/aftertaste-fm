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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val completionClient = LlmCompletionClient(config, json)

    override suspend fun plan(
        context: RecommendationContext,
        hostConfig: HostConfig,
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): LlmShowPlan? {
        if (candidates.size < 3) return null
        return runCatching {
            val outputText = completionClient.complete(
                systemPrompt(hostConfig),
                userPrompt(context, candidates, tasteProfile, taggedCandidates),
                maxTokens = 1400,
                jsonMode = true,
                responseSchema = responseFormatSchema()
            ) ?: return null
            json.decodeFromString<LlmShowPlan>(outputText).normalize(candidates)
        }.getOrNull()
    }

    private fun systemPrompt(hostConfig: HostConfig): String =
        """
        You are Aftertaste, a restrained private late-night radio director.
        Create a radio show from ONLY the provided candidate track ids.
        Each segment is a chapter. The first trackId is the chapter lead: the host will speak over that track's opening, then the rest of the chapter plays without interruption.
        Do not introduce every song. Talk once per chapter, not before every track.
        Host language must be ${hostConfig.hostLanguage}. For v0.1, write natural English even when tracks are Chinese.
        Style: ${hostConfig.hostStyle}. Calm, specific, companionable, never oily, never encyclopedic.
        Host scripts should feel like a real late-night radio break, not product copy.

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
        candidates: List<Track>,
        tasteProfile: TasteProfile?,
        taggedCandidates: List<TaggedTrack>
    ): String {
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

        return """
        User request: ${context.mood ?: "Generate today's show."}
        Intent: ${context.intent}
        Context signals: ${context.recentSignals.joinToString(", ")}
        Variation seed: ${context.variationSeed ?: "none"}
        Local time: ${context.localTime}
        Weather context: ${context.weather?.let { "${friendlyPlaceName(it.locationName)}, ${it.condition}, ${it.temperatureC}C, feels ${it.apparentTemperatureC ?: it.temperatureC}C" } ?: "none"}
        Catalog constraint: ${catalogConstraint(context)}
        Artist constraint: ${artistConstraint(context)}

        Taste profile:
        $tasteProfileText

        Candidate tracks:
        $candidateText

        Build 4 segments. Each segment should contain exactly 3 trackIds when possible.
        Use 12 unique trackIds when possible. Do not repeat the same track in different chapters.
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

    private fun LlmShowPlan.normalize(candidates: List<Track>): LlmShowPlan? {
        val validIds = candidates.map { it.id }.toSet()
        val fallbackIds = candidates.map { it.id }
        val usedIds = linkedSetOf<String>()
        val cleanedSegments = segments.mapNotNull { segment ->
            val ids = segment.trackIds
                .filter { it in validIds && it !in usedIds }
                .distinct()
                .toMutableList()
            fallbackIds.forEach { fallbackId ->
                if (ids.size < 3 && fallbackId !in usedIds && fallbackId !in ids) ids += fallbackId
            }
            val finalIds = ids.take(3)
            if (finalIds.size < 3) {
                null
            } else {
                usedIds += finalIds
                segment.copy(trackIds = finalIds)
            }
        }.take(4)
        if (cleanedSegments.size < 2) return null
        return copy(
            title = title.take(80).ifBlank { "Aftertaste Session" },
            rationale = rationale.take(400),
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

class AgentChatService(
    private val config: LlmRuntimeConfig?
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val completionClient = config?.let { LlmCompletionClient(it, json) }

    suspend fun reply(message: String, playback: PlaybackState, hostConfig: HostConfig): AgentChatResponse {
        val fallbackDecision = fallbackReply(message, playback)
        val fallbackMessage = fallbackDecision.message
            ?: "Tell me what the room needs, and I can build a hosted radio chapter around it."
        val runtimeConfig = config ?: return AgentChatResponse(
            message = fallbackMessage,
            shouldPlan = fallbackDecision.shouldPlan,
            command = fallbackDecision.command,
            mode = "local-fallback"
        )
        val client = completionClient ?: return AgentChatResponse(
            message = fallbackMessage,
            shouldPlan = fallbackDecision.shouldPlan,
            command = fallbackDecision.command,
            mode = "local-fallback"
        )

        return runCatching {
            val intent = client.complete(
                intentSystemPrompt(hostConfig),
                intentUserPrompt(message, playback),
                maxTokens = 320,
                jsonMode = true,
                responseSchema = agentIntentSchema()
            )
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { parseAgentIntent(it, fallbackMessage) }
                ?: fallbackDecision
            AgentChatResponse(
                message = intent.message?.take(900)?.ifBlank { fallbackMessage } ?: fallbackMessage,
                mode = "llm-chat:${runtimeConfig.displayName}",
                shouldPlan = intent.shouldPlan,
                command = intent.command
            )
        }.getOrElse {
            AgentChatResponse(
                message = fallbackMessage,
                shouldPlan = fallbackDecision.shouldPlan,
                command = fallbackDecision.command,
                mode = "local-fallback"
            )
        }
    }

    private fun intentSystemPrompt(hostConfig: HostConfig): String =
        """
        You are Aftertaste, the private AI radio host and intent router inside a music player.
        Interpret the user's full message semantically across languages; do not rely on a fixed keyword list.
        Reply in ${hostConfig.hostLanguage}, usually in 1-3 short sentences.
        Decide shouldPlan=true when the user asks to create, change, retune, debug, diversify, or otherwise affect the music/show direction.
        Decide command only for clear playback controls: next, previous, pause, play, or now.
        For recommendation complaints such as repeated songs for the same request, acknowledge the issue and set shouldPlan=true when the user wants a correction or retune.
        Avoid product-planning jargon and avoid claiming facts about a song unless they are present in the current playback context.
        Return only JSON: {"message":"...","shouldPlan":false,"command":null}
        """.trimIndent()

    private fun intentUserPrompt(message: String, playback: PlaybackState): String =
        """
        Current show: ${playback.showTitle ?: "none"}
        Current item type: ${playback.currentItem?.type ?: "none"}
        Current segment: ${playback.segmentTitle ?: "none"}
        Current track: ${playback.currentItem?.track?.title ?: "none"} by ${playback.currentItem?.track?.artist ?: "unknown"}
        User message: $message
        """.trimIndent()

    private fun parseAgentIntent(output: String, fallbackMessage: String): AgentIntentDecision =
        runCatching {
            val parsed = json.decodeFromString<AgentIntentOutput>(output)
            AgentIntentDecision(
                message = parsed.message.take(900).ifBlank { fallbackMessage },
                shouldPlan = parsed.shouldPlan,
                command = parsed.command?.takeIf { it in playbackCommands }
            )
        }.getOrElse {
            AgentIntentDecision(message = fallbackMessage)
        }

    private fun agentIntentSchema(): JsonObject = buildJsonObject {
        put("type", "json_schema")
        put("name", "aftertaste_agent_intent")
        put("strict", true)
        put("schema", buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("required", buildJsonArray {
                add(JsonPrimitive("message"))
                add(JsonPrimitive("shouldPlan"))
                add(JsonPrimitive("command"))
            })
            put("properties", buildJsonObject {
                put("message", stringSchema())
                put("shouldPlan", buildJsonObject {
                    put("type", "boolean")
                })
                put("command", buildJsonObject {
                    put("type", buildJsonArray {
                        add(JsonPrimitive("string"))
                        add(JsonPrimitive("null"))
                    })
                    put("enum", buildJsonArray {
                        playbackCommands.forEach { add(JsonPrimitive(it)) }
                        add(JsonNull)
                    })
                })
            })
        })
    }

    private fun fallbackReply(message: String, playback: PlaybackState): AgentIntentDecision {
        val decision = fallbackDecision(message, playback)
        if (decision.message != null) return decision

        val lower = message.lowercase()
        val reply = when {
            "help" in lower || "what can you" in lower ->
                "I can tune the station by mood, language, energy, or scene; skip, pause, resume, or go back; and talk about what is playing. Try something like \"more English and less sad\" or \"quiet songs for late-night coding.\""
            playback.currentItem != null ->
                "I am here with the current chapter. Tell me if you want the music softer, brighter, more English, less sad, or just ask about what is playing."
            else ->
                "Tell me what the room needs, and I can build a hosted radio chapter around it."
        }
        return decision.copy(message = reply)
    }

    companion object {
        fun fromEnvironment(): AgentChatService =
            AgentChatService(LlmRuntimeConfig.fromEnvironment(LlmUseCase.Chat))
    }
}

@Serializable
private data class AgentIntentOutput(
    val message: String = "",
    val shouldPlan: Boolean = false,
    val command: String? = null
)

private data class AgentIntentDecision(
    val message: String? = null,
    val shouldPlan: Boolean = false,
    val command: String? = null
)

private val playbackCommands = setOf("next", "previous", "pause", "play", "now")

private fun fallbackDecision(message: String, playback: PlaybackState): AgentIntentDecision {
    val text = message.trim().lowercase()
    if (isExplicitCommand(text, listOf("next", "skip", "skip this", "下一首", "换歌", "跳过"))) {
        return AgentIntentDecision(message = "Skipping to the next item.", command = "next")
    }
    if (isExplicitCommand(text, listOf("previous", "prev", "back", "上一首", "回上一首"))) {
        return AgentIntentDecision(message = "Going back one item.", command = "previous")
    }
    if (isExplicitCommand(text, listOf("pause", "stop", "暂停", "停一下"))) {
        return AgentIntentDecision(message = "Paused.", command = "pause")
    }
    if (isExplicitCommand(text, listOf("play", "resume", "continue", "继续", "播放"))) {
        return AgentIntentDecision(message = "Playing.", command = "play")
    }
    if (isExplicitCommand(text, listOf("what's playing", "what is playing", "now playing", "这是什么", "现在放什么", "正在放什么"))) {
        val current = playback.currentItem
        val track = current?.track
        val now = if (track != null) {
            "On air: ${track.title} by ${track.artist}."
        } else {
            "Nothing is on air yet. Generate a show first."
        }
        return AgentIntentDecision(message = now, command = "now")
    }

    return AgentIntentDecision()
}

private fun isExplicitCommand(text: String, commands: List<String>): Boolean =
    commands.any { command -> text == command || text == "$command." || text == "$command please" }

enum class LlmUseCase {
    Planner,
    Chat
}

enum class LlmApiProvider(
    val displayName: String,
    val defaultBaseUrl: String
) {
    OpenAiResponses("openai-responses", "https://api.openai.com/v1"),
    OpenAiCompatible("openai-compatible", "https://api.openai.com/v1"),
    Anthropic("anthropic", "https://api.anthropic.com")
}

data class LlmRuntimeConfig(
    val provider: LlmApiProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String
) {
    val displayName: String = "${provider.displayName}/$model"

    fun endpoint(path: String): String =
        "${baseUrl.trimEnd('/')}/$path"

    companion object {
        fun fromEnvironment(useCase: LlmUseCase): LlmRuntimeConfig? {
            val providerName = Env.value("LLM_PROVIDER")?.lowercase()
            val provider = when (providerName) {
                "openai", "openai-responses", "responses" -> LlmApiProvider.OpenAiResponses
                "compatible", "openai-compatible", "chat-completions", "minimax", "deepseek", "qwen", "moonshot" -> LlmApiProvider.OpenAiCompatible
                "anthropic", "claude" -> LlmApiProvider.Anthropic
                else -> when {
                    Env.value("ANTHROPIC_API_KEY") != null && Env.value("LLM_API_KEY") == null -> LlmApiProvider.Anthropic
                    Env.value("LLM_BASE_URL") != null -> LlmApiProvider.OpenAiCompatible
                    else -> LlmApiProvider.OpenAiResponses
                }
            }
            val apiKey = when (provider) {
                LlmApiProvider.Anthropic -> Env.value("LLM_API_KEY") ?: Env.value("ANTHROPIC_API_KEY")
                else -> Env.value("LLM_API_KEY")
            } ?: return null
            val model = when (useCase) {
                LlmUseCase.Chat -> Env.value("LLM_CHAT_MODEL")
                    ?: Env.value("LLM_MODEL")
                    ?: defaultModel(provider)
                LlmUseCase.Planner -> Env.value("LLM_MODEL")
                    ?: defaultModel(provider)
            }
            val baseUrl = Env.value("LLM_BASE_URL") ?: provider.defaultBaseUrl
            return LlmRuntimeConfig(provider, apiKey, model, baseUrl)
        }

        private fun defaultModel(provider: LlmApiProvider): String =
            when (provider) {
                LlmApiProvider.Anthropic -> "claude-3-5-haiku-latest"
                else -> "gpt-5.2"
            }
    }
}

private class LlmCompletionClient(
    private val config: LlmRuntimeConfig,
    private val json: Json
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        jsonMode: Boolean,
        responseSchema: JsonObject?
    ): String? {
        val responseText = when (config.provider) {
            LlmApiProvider.OpenAiResponses -> postOpenAiResponses(systemPrompt, userPrompt, maxTokens, responseSchema)
            LlmApiProvider.OpenAiCompatible -> postChatCompletions(systemPrompt, userPrompt, maxTokens, jsonMode)
            LlmApiProvider.Anthropic -> postAnthropic(systemPrompt, userPrompt, maxTokens)
        }
        return extractModelText(json.parseToJsonElement(responseText), config.provider)
            ?.trim()
            ?.trim('`')
            ?.removePrefix("json")
            ?.trim()
    }

    private suspend fun postOpenAiResponses(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        responseSchema: JsonObject?
    ): String =
        client.post(config.endpoint("responses")) {
            bearerAuth(config.apiKey)
            header("OpenAI-Beta", "responses=v1")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", config.model)
                put("input", buildJsonArray {
                    add(buildMessage("system", systemPrompt))
                    add(buildMessage("user", userPrompt))
                })
                if (responseSchema != null) {
                    put("text", buildJsonObject {
                        put("format", responseSchema)
                    })
                }
                put("max_output_tokens", maxTokens)
                put("temperature", Env.value("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.75)
            })
        }.body()

    private suspend fun postChatCompletions(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        jsonMode: Boolean
    ): String =
        client.post(config.endpoint("chat/completions")) {
            bearerAuth(config.apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", config.model)
                put("messages", buildJsonArray {
                    add(buildMessage("system", systemPrompt))
                    add(buildMessage("user", userPrompt))
                })
                put("max_tokens", maxTokens)
                put("temperature", Env.value("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.75)
                if (jsonMode) {
                    put("response_format", buildJsonObject {
                        put("type", "json_object")
                    })
                }
            })
        }.body()

    private suspend fun postAnthropic(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): String =
        client.post(config.endpoint("v1/messages")) {
            header("x-api-key", config.apiKey)
            header("anthropic-version", Env.value("ANTHROPIC_VERSION") ?: "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", config.model)
                put("system", systemPrompt)
                put("messages", buildJsonArray {
                    add(buildMessage("user", userPrompt))
                })
                put("max_tokens", maxTokens)
                put("temperature", Env.value("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.75)
            })
        }.body()
}

private fun extractModelText(element: JsonElement, provider: LlmApiProvider): String? =
    when (provider) {
        LlmApiProvider.OpenAiResponses -> extractOutputText(element)
        LlmApiProvider.OpenAiCompatible -> extractChatCompletionText(element) ?: extractOutputText(element)
        LlmApiProvider.Anthropic -> extractAnthropicText(element) ?: extractOutputText(element)
    }

private fun extractChatCompletionText(element: JsonElement): String? =
    runCatching {
        element.jsonObject["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

private fun extractAnthropicText(element: JsonElement): String? =
    runCatching {
        element.jsonObject["content"]
            ?.jsonArray
            ?.firstOrNull { item ->
                item.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
            }
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

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
        "Hard preference: use ${artists.joinToString(", ")} tracks as the main catalog. If enough candidate tracks by this artist are present, selected tracks should mainly be by this artist, but duplicate titles should still be avoided."
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

fun friendlyPlaceName(locationName: String): String =
    locationName
        .split(",")
        .firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: locationName.trim()
