package fm.aftertaste

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class AgentChatService(
    private val config: LlmRuntimeConfig?
) {
    private val logger = LoggerFactory.getLogger(AgentChatService::class.java)
    private val json: Json = HttpClients.sharedJson
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
                responseSchema = agentIntentSchema(),
                cacheSystemPrompt = true
            )
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { parseAgentIntent(it, fallbackMessage) }
                ?: fallbackDecision
            AgentChatResponse(
                message = intent.message?.ifBlank { fallbackMessage } ?: fallbackMessage,
                mode = "llm-chat:${runtimeConfig.displayName}",
                shouldPlan = intent.shouldPlan,
                command = intent.command,
                routingIntent = intent.routingIntent.takeIf { intent.shouldPlan }
            )
        }.getOrElse { error ->
            logger.warn("Agent chat fallback: {}", error.message)
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
        When shouldPlan=true, fill routingIntent with structured preferences. Put explicit genres, styles, scenes, and sound descriptors in extraTags as lowercase kebab-case. Use null for unknown scalar fields and [] for empty lists.
        Return only JSON: {"message":"...","shouldPlan":false,"command":null,"routingIntent":null}
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
                message = parsed.message.ifBlank { fallbackMessage },
                shouldPlan = parsed.shouldPlan,
                command = parsed.command?.takeIf { it in playbackCommands },
                routingIntent = parsed.routingIntent?.clean()
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
                add(JsonPrimitive("routingIntent"))
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
                put("routingIntent", routingIntentSchema())
            })
        })
    }

    private fun routingIntentSchema(): JsonObject = buildJsonObject {
        put("type", buildJsonArray {
            add(JsonPrimitive("object"))
            add(JsonPrimitive("null"))
        })
        put("additionalProperties", false)
        put("required", buildJsonArray {
            listOf("language", "energy", "routine", "moodTag", "avoid", "artists", "extraTags").forEach {
                add(JsonPrimitive(it))
            }
        })
        put("properties", buildJsonObject {
            put("language", nullableStringSchema())
            put("energy", nullableStringSchema())
            put("routine", nullableStringSchema())
            put("moodTag", nullableStringSchema())
            put("avoid", stringArraySchema())
            put("artists", stringArraySchema())
            put("extraTags", stringArraySchema())
        })
    }

    private fun fallbackReply(message: String, playback: PlaybackState): AgentIntentDecision {
        val decision = explicitCommandDecision(message, playback)
        if (decision.message != null) return decision

        val reply = if (playback.currentItem != null) {
            "I am here with the current chapter. Tell me if you want the music softer, brighter, more English, less sad, or just ask about what is playing."
        } else {
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
    val command: String? = null,
    val routingIntent: RoutingIntent? = null
)

internal data class AgentIntentDecision(
    val message: String? = null,
    val shouldPlan: Boolean = false,
    val command: String? = null,
    val routingIntent: RoutingIntent? = null
)

internal val playbackCommands = setOf("next", "previous", "pause", "play", "now")

/**
 * Explicit command parser. Per CLAUDE.md, "React may handle only explicit player commands"; this is the
 * server-side mirror of the same idea: tight, single-phrase matching so deterministic playback controls
 * still work when the LLM is unavailable. Keep this small — broader intent should go through the LLM.
 */
internal fun explicitCommandDecision(message: String, playback: PlaybackState): AgentIntentDecision {
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
        val track = playback.currentItem?.track
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

private fun nullableStringSchema(): JsonObject = buildJsonObject {
    put("type", buildJsonArray {
        add(JsonPrimitive("string"))
        add(JsonPrimitive("null"))
    })
}

private fun stringArraySchema(): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", stringSchema())
}

private fun RoutingIntent.clean(): RoutingIntent =
    copy(
        language = language?.trim()?.takeIf { it.isNotBlank() },
        energy = energy?.trim()?.takeIf { it.isNotBlank() },
        routine = routine?.trim()?.takeIf { it.isNotBlank() },
        moodTag = moodTag?.trim()?.takeIf { it.isNotBlank() },
        avoid = avoid.cleaned(),
        artists = artists.cleaned(),
        extraTags = extraTags.map { safeFileStem(it).lowercase() }.cleaned()
    )

private fun List<String>.cleaned(): List<String> =
    map { it.trim() }.filter { it.isNotBlank() }.distinct()
