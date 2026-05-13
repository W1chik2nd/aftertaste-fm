package fm.aftertaste

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val UPSTREAM_ERROR_SNIPPET_CHARS = 240

class LlmCompletionClient(
    private val config: LlmRuntimeConfig,
    private val json: Json = HttpClients.sharedJson,
    private val client: HttpClient = HttpClients.shared
) {
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        jsonMode: Boolean,
        responseSchema: JsonObject?,
        cacheSystemPrompt: Boolean = false
    ): String? {
        val responseText = when (config.provider) {
            LlmApiProvider.OpenAiResponses -> postOpenAiResponses(systemPrompt, userPrompt, maxTokens, responseSchema)
            LlmApiProvider.OpenAiCompatible -> postChatCompletions(systemPrompt, userPrompt, maxTokens, jsonMode)
            LlmApiProvider.Anthropic -> postAnthropic(systemPrompt, userPrompt, maxTokens, cacheSystemPrompt)
        }
        return extractModelText(json.parseToJsonElement(responseText), config.provider)
            ?.trim()
            ?.removeSurrounding("```json", "```")
            ?.removeSurrounding("```", "```")
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
        }.checkedBody()

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
        }.checkedBody()

    private suspend fun postAnthropic(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        cacheSystemPrompt: Boolean
    ): String =
        client.post(config.endpoint("v1/messages")) {
            header("x-api-key", config.apiKey)
            header("anthropic-version", Env.value("ANTHROPIC_VERSION") ?: "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", config.model)
                if (cacheSystemPrompt) {
                    put("system", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", systemPrompt)
                            put("cache_control", buildJsonObject {
                                put("type", "ephemeral")
                            })
                        })
                    })
                } else {
                    put("system", systemPrompt)
                }
                put("messages", buildJsonArray {
                    add(buildMessage("user", userPrompt))
                })
                put("max_tokens", maxTokens)
                put("temperature", Env.value("LLM_TEMPERATURE")?.toDoubleOrNull() ?: 0.75)
            })
        }.checkedBody()
}

class UpstreamApiException(message: String) : RuntimeException(message)

private suspend fun HttpResponse.checkedBody(): String {
    val text = bodyAsText()
    if (status.isSuccess()) return text
    val snippet = text.take(UPSTREAM_ERROR_SNIPPET_CHARS).trim()
    val detail = if (snippet.isBlank()) "" else ": $snippet"
    throw UpstreamApiException("LLM upstream failed: ${status.value} ${status.description}$detail")
}

private fun extractModelText(element: JsonElement, provider: LlmApiProvider): String? =
    when (provider) {
        LlmApiProvider.OpenAiResponses -> extractOutputText(element)
        LlmApiProvider.OpenAiCompatible -> extractChatCompletionText(element) ?: extractOutputText(element)
        LlmApiProvider.Anthropic -> extractAnthropicText(element) ?: extractOutputText(element)
    }

private fun extractChatCompletionText(element: JsonElement): String? =
    (element as? JsonObject)
        ?.get("choices")
        ?.let { it as? JsonArray }
        ?.firstOrNull()
        ?.let { it as? JsonObject }
        ?.get("message")
        ?.let { it as? JsonObject }
        ?.get("content")
        ?.jsonPrimitive
        ?.contentOrNull

private fun extractAnthropicText(element: JsonElement): String? =
    (element as? JsonObject)
        ?.get("content")
        ?.let { it as? JsonArray }
        ?.firstOrNull { item ->
            (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text"
        }
        ?.let { it as? JsonObject }
        ?.get("text")
        ?.jsonPrimitive
        ?.contentOrNull

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

internal fun buildMessage(role: String, content: String): JsonObject =
    buildJsonObject {
        put("role", role)
        put("content", content)
    }

internal fun stringSchema(): JsonObject = buildJsonObject {
    put("type", "string")
}

fun friendlyPlaceName(locationName: String): String =
    locationName
        .split(",")
        .firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: locationName.trim()
