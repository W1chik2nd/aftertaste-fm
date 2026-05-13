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
import java.time.OffsetDateTime
import kotlin.random.Random

private const val FISH_ERROR_BODY_CHARS = 220
private const val HOST_CACHE_KEY_CHARS = 24
private const val TTS_THROWAWAY_RETENTION_MS = 24L * 60 * 60 * 1000

class HostVoiceService(
    private val httpClient: HttpClient = HttpClients.shared
) {
    private val logger = LoggerFactory.getLogger(HostVoiceService::class.java)

    private val fishApiKey = Env.value("FISH_API_KEY")
    private val fishEndpoint = Env.value("FISH_TTS_ENDPOINT") ?: "https://api.fish.audio/v1/tts"
    private val fishModel = Env.value("FISH_TTS_MODEL") ?: "s2-pro"
    private val fishVoiceId = Env.value("FISH_VOICE_ID")
    private val fishFormat = Env.value("FISH_TTS_FORMAT") ?: "mp3"
    private val fishLatency = Env.value("FISH_TTS_LATENCY") ?: "normal"
    private val fishTemperature = Env.value("FISH_TTS_TEMPERATURE")?.toDoubleOrNull() ?: 0.7
    private val fishTopP = Env.value("FISH_TTS_TOP_P")?.toDoubleOrNull() ?: 0.7
    private val cacheEnabled = Env.value("FISH_TTS_CACHE")?.lowercase() == "true"
    private val cacheDirectory: Path = Env.path("TTS_CACHE_DIR", "cache/tts")

    fun generateHostScript(
        segmentTitle: String,
        tracks: List<Track>,
        context: RecommendationContext,
        chapterIndex: Int = 0
    ): String {
        val lead = tracks.firstOrNull()
        val clock = OffsetDateTime.now()
        val timeText = "%d:%02d".format(clock.hour, clock.minute)
        val mood = hostMoodLabel(context)
        val station = context.stationStyle?.label?.lowercase() ?: "the station"
        val leadLine = lead?.let { "Now, ${it.artist}, ${it.title}." } ?: "Let this chapter take its time."
        val leadIntro = lead?.let { "${it.title} by ${it.artist}" } ?: "this next record"
        val weather = context.weather?.let {
            " Outside in ${friendlyPlaceName(it.locationName)}, it is ${it.condition} and ${"%.0f".format(it.temperatureC)} degrees."
        }.orEmpty()
        val seed = listOf(segmentTitle, context.variationSeed, lead?.id).joinToString("|").hashCode()
        val firstChapterOpenings = listOf(
            "It is $timeText now.$weather We start quietly, with $leadIntro close enough to feel personal and far enough away to leave some room.",
            "$timeText.$weather This first chapter does not need a grand entrance. $leadIntro can come in at the pace of $station.",
            "The hour is $timeText.$weather For the first record, we keep the lights low and let $leadIntro set the pace without announcing itself too hard.",
            "${weather.trimStart()} The clock says $timeText, and this opening chapter is more about settling than declaring. $leadIntro gives us that first soft edge."
        )
        return when (chapterIndex) {
            0 -> "${firstChapterOpenings.randomBy(seed)} It keeps close to $mood without forcing the feeling into shape. $leadLine"
            1 -> listOf(
                "A few songs in, the station has changed texture. This chapter stays with what remains after the noise falls away. $leadIntro gives that feeling a center, then the next songs get to move cleanly around it. $leadLine",
                "We turn a little, but we do not break the spell. $leadIntro keeps the room low and steady, the kind of song that lets memory be present without making a speech out of it. $leadLine",
                "The second chapter should feel less like a reset and more like a handoff. $leadIntro carries the thread forward, soft at the edges and clear in the middle. $leadLine"
            ).randomBy(seed)
            2 -> listOf(
                "This is where the room gets a little wider. $leadIntro gives the chapter more air while keeping the pulse of $station intact. $leadLine",
                "Now we let the show breathe out. $leadIntro opens a wider lane, still careful, still close, but no longer holding every thought in place. $leadLine",
                "The middle has done its quiet work, so this chapter can lift without rushing. $leadIntro is the door opening a little farther. $leadLine"
            ).randomBy(seed)
            else -> listOf(
                "For the last chapter, we do not need to explain too much. The point is to leave the hour somewhere softer than where it began, and $leadIntro feels right for that. $leadLine",
                "We take the final turn without tying a ribbon around it. $leadIntro can carry us out slowly, with enough distance to feel calm and enough warmth to stay near. $leadLine",
                "This last stretch is for letting the room settle. $leadIntro does not demand an answer; it just gives the ending somewhere gentle to land. $leadLine"
            ).randomBy(seed)
        }
    }

    private fun hostMoodLabel(context: RecommendationContext): String {
        val routing = context.routing
        return when {
            "too-sad" in routing.avoid -> "something soft without letting the room get too heavy"
            routing.language?.startsWith("zh") == true && routing.energy == "low" -> "a low-energy Chinese indie thread"
            routing.routine == "late-night-coding" -> "late-night focus with the edges softened"
            routing.energy == "low" -> "a quiet, low-energy stretch"
            context.mood.isNullOrBlank() -> context.stationStyle?.hostStyle ?: "that quiet feeling where memory is present, but not loud"
            else -> "the feeling you asked for"
        }
    }

    suspend fun synthesize(script: String): HostVoiceAsset {
        val digest = cacheKey(script)
        if (fishApiKey.isNullOrBlank()) {
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
            performFishRequest(script, outputFile, digest)
        }.getOrElse { error ->
            logger.warn("Fish TTS skipped: {}", error.message)
            HostVoiceAsset(script = script, audioUrl = null, cacheKey = digest)
        }
    }

    private suspend fun performFishRequest(script: String, outputFile: Path, digest: String): HostVoiceAsset =
        withContext(Dispatchers.IO) {
            Files.createDirectories(cacheDirectory)
            val response = httpClient.post(fishEndpoint) {
                header("Authorization", "Bearer $fishApiKey")
                header("model", fishModel)
                contentType(ContentType.Application.Json)
                setBody(buildFishPayload(script).toString())
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

    private fun cacheKey(script: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$fishModel|${fishVoiceId.orEmpty()}|$fishFormat|$script".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(HOST_CACHE_KEY_CHARS)

    private fun buildFishPayload(script: String) = buildJsonObject {
        put("text", script)
        if (!fishVoiceId.isNullOrBlank()) put("reference_id", fishVoiceId)
        put("temperature", fishTemperature)
        put("top_p", fishTopP)
        putJsonObject("prosody") {
            put("speed", Env.value("FISH_TTS_SPEED")?.toDoubleOrNull() ?: 1.0)
            put("volume", Env.value("FISH_TTS_VOLUME")?.toDoubleOrNull() ?: 4.0)
            put("normalize_loudness", true)
        }
        put("chunk_length", 300)
        put("normalize", true)
        put("format", fishFormat)
        put("sample_rate", Env.value("FISH_TTS_SAMPLE_RATE")?.toIntOrNull() ?: 44100)
        if (fishFormat == "mp3") put("mp3_bitrate", Env.value("FISH_TTS_MP3_BITRATE")?.toIntOrNull() ?: 128)
        put("latency", fishLatency)
        put("max_new_tokens", Env.value("FISH_TTS_MAX_NEW_TOKENS")?.toIntOrNull() ?: 1024)
        put("repetition_penalty", Env.value("FISH_TTS_REPETITION_PENALTY")?.toDoubleOrNull() ?: 1.2)
        put("min_chunk_length", 50)
        put("condition_on_previous_chunks", true)
        put("early_stop_threshold", 1.0)
    }
}

internal fun <T> List<T>.randomBy(seed: Int): T = this[Random(seed).nextInt(size)]
