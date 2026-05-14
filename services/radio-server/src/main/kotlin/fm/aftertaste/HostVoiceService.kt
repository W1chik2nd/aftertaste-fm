package fm.aftertaste

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

private const val FISH_ERROR_BODY_CHARS = 220
private const val HOST_CACHE_KEY_CHARS = 24
private const val TTS_THROWAWAY_RETENTION_MS = 24L * 60 * 60 * 1000

class HostVoiceService(
    private val httpClient: HttpClient = HttpClients.shared
) {
    private val logger = LoggerFactory.getLogger(HostVoiceService::class.java)

    private val fishApiKey = Env.value("FISH_API_KEY")
    private val fishVoiceId = Env.value("FISH_VOICE_ID")
    // Chinese host gets its own Fish credentials; falls back to the default ones when unset.
    private val fishApiKeyZh = Env.value("FISH_API_KEY_ZH")
    private val fishVoiceIdZh = Env.value("FISH_VOICE_ID_ZH")
    private val fishEndpoint = Env.value("FISH_TTS_ENDPOINT") ?: "https://api.fish.audio/v1/tts"
    private val fishModel = Env.value("FISH_TTS_MODEL") ?: "s2-pro"
    private val fishFormat = Env.value("FISH_TTS_FORMAT") ?: "mp3"
    private val fishLatency = Env.value("FISH_TTS_LATENCY") ?: "normal"
    private val fishTemperature = Env.value("FISH_TTS_TEMPERATURE")?.toDoubleOrNull() ?: 0.7
    private val fishTopP = Env.value("FISH_TTS_TOP_P")?.toDoubleOrNull() ?: 0.7
    private val fishSpeed = Env.value("FISH_TTS_SPEED")?.toDoubleOrNull() ?: 1.0
    // Mandarin at speed 1.0 reads rushed and flat; the Chinese host is slower by default.
    private val fishSpeedZh = Env.value("FISH_TTS_SPEED_ZH")?.toDoubleOrNull() ?: 0.85
    private val fishVolume = Env.value("FISH_TTS_VOLUME")?.toDoubleOrNull() ?: 4.0
    private val fishSampleRate = Env.value("FISH_TTS_SAMPLE_RATE")?.toIntOrNull() ?: 44100
    private val fishMp3Bitrate = Env.value("FISH_TTS_MP3_BITRATE")?.toIntOrNull() ?: 128
    private val fishMaxNewTokens = Env.value("FISH_TTS_MAX_NEW_TOKENS")?.toIntOrNull() ?: 1024
    private val fishRepetitionPenalty = Env.value("FISH_TTS_REPETITION_PENALTY")?.toDoubleOrNull() ?: 1.2
    private val cacheEnabled = Env.value("FISH_TTS_CACHE")?.lowercase() == "true"
    private val cacheDirectory: Path = Env.path("TTS_CACHE_DIR", "cache/tts")

    /**
     * Fish settings for a given host language. Chinese uses the dedicated key/voice when set,
     * otherwise it reuses the default ones so a single-key setup still produces a Chinese host.
     * `speed` is per-language: Mandarin needs a slower delivery to not sound like a reading machine.
     */
    private data class VoiceProfile(val apiKey: String?, val voiceId: String?, val speed: Double)

    private fun voiceProfileFor(hostLanguage: String): VoiceProfile =
        if (isChineseHostLanguage(hostLanguage)) {
            VoiceProfile(
                apiKey = fishApiKeyZh?.takeIf { it.isNotBlank() } ?: fishApiKey,
                voiceId = fishVoiceIdZh?.takeIf { it.isNotBlank() } ?: fishVoiceId,
                speed = fishSpeedZh
            )
        } else {
            VoiceProfile(apiKey = fishApiKey, voiceId = fishVoiceId, speed = fishSpeed)
        }

    suspend fun synthesize(script: String, hostLanguage: String = "en-US"): HostVoiceAsset {
        val profile = voiceProfileFor(hostLanguage)
        val digest = cacheKey(script, profile.voiceId)
        if (profile.apiKey.isNullOrBlank()) {
            return HostVoiceAsset(script = script, audioUrl = null, cacheKey = digest)
        }

        val extension = if (fishFormat == "wav") "wav" else fishFormat
        val outputFile = if (cacheEnabled) {
            cacheDirectory.resolve("$digest.$extension")
        } else {
            sweepStaleThrowaways(extension)
            cacheDirectory.resolve("$digest-${System.currentTimeMillis()}.$extension")
        }
        if (cacheEnabled && Files.exists(outputFile)) {
            return HostVoiceAsset(script = script, audioUrl = "/media/tts/${outputFile.fileName}", cacheKey = digest)
        }

        return runCatching {
            performFishRequest(script, outputFile, digest, profile)
        }.getOrElse { error ->
            logger.warn("Fish TTS skipped: {}", error.message)
            HostVoiceAsset(script = script, audioUrl = null, cacheKey = digest)
        }
    }

    private suspend fun performFishRequest(
        script: String,
        outputFile: Path,
        digest: String,
        profile: VoiceProfile
    ): HostVoiceAsset =
        withContext(Dispatchers.IO) {
            Files.createDirectories(cacheDirectory)
            val response = httpClient.post(fishEndpoint) {
                header("Authorization", "Bearer ${profile.apiKey}")
                header("model", fishModel)
                contentType(ContentType.Application.Json)
                setBody(buildFishPayload(script, profile).toString())
            }
            if (response.status.value !in 200..299) {
                val reason = response.bodyAsText().take(FISH_ERROR_BODY_CHARS)
                logger.warn("Fish TTS HTTP {}: {}", response.status.value, reason)
                return@withContext HostVoiceAsset(script = script, audioUrl = null, cacheKey = digest)
            }
            val tempFile = outputFile.resolveSibling("${outputFile.fileName}.part")
            Files.newOutputStream(
                tempFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { sink ->
                response.bodyAsChannel().copyTo(sink)
            }
            try {
                Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING)
            }
            HostVoiceAsset(script = script, audioUrl = "/media/tts/${outputFile.fileName}", cacheKey = digest)
        }

    private fun sweepStaleThrowaways(extension: String) {
        if (!Files.exists(cacheDirectory)) return
        val cutoff = System.currentTimeMillis() - TTS_THROWAWAY_RETENTION_MS
        try {
            Files.list(cacheDirectory).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName.toString()
                        name.endsWith(".$extension") && name.contains('-') &&
                            Files.getLastModifiedTime(path).toMillis() < cutoff
                    }
                    .forEach { path ->
                        runCatching { Files.deleteIfExists(path) }
                            .onFailure { logger.debug("Could not delete stale TTS file {}: {}", path, it.message) }
                    }
            }
        } catch (cause: java.io.IOException) {
            logger.debug("TTS cache sweep failed: {}", cause.message)
        }
    }

    private fun cacheKey(script: String, voiceId: String?): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$fishModel|${voiceId.orEmpty()}|$fishFormat|$script".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(HOST_CACHE_KEY_CHARS)

    private fun buildFishPayload(script: String, profile: VoiceProfile) = buildJsonObject {
        put("text", script)
        if (!profile.voiceId.isNullOrBlank()) put("reference_id", profile.voiceId)
        put("temperature", fishTemperature)
        put("top_p", fishTopP)
        putJsonObject("prosody") {
            put("speed", profile.speed)
            put("volume", fishVolume)
            put("normalize_loudness", true)
        }
        put("chunk_length", 300)
        put("normalize", true)
        put("format", fishFormat)
        put("sample_rate", fishSampleRate)
        if (fishFormat == "mp3") put("mp3_bitrate", fishMp3Bitrate)
        put("latency", fishLatency)
        put("max_new_tokens", fishMaxNewTokens)
        put("repetition_penalty", fishRepetitionPenalty)
        put("min_chunk_length", 50)
        put("condition_on_previous_chunks", true)
        put("early_stop_threshold", 1.0)
    }
}
