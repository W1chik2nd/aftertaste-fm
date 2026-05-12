package fm.aftertaste

import java.security.MessageDigest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.Duration
import kotlin.math.min
import kotlin.random.Random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class HostVoiceService {
    private val fishApiKey = Env.value("FISH_API_KEY")
    private val fishEndpoint = Env.value("FISH_TTS_ENDPOINT") ?: "https://api.fish.audio/v1/tts"
    private val fishModel = Env.value("FISH_TTS_MODEL") ?: "s2-pro"
    private val fishVoiceId = Env.value("FISH_VOICE_ID")
    private val fishFormat = Env.value("FISH_TTS_FORMAT") ?: "mp3"
    private val fishLatency = Env.value("FISH_TTS_LATENCY") ?: "normal"
    private val fishTemperature = Env.value("FISH_TTS_TEMPERATURE")?.toDoubleOrNull() ?: 0.7
    private val fishTopP = Env.value("FISH_TTS_TOP_P")?.toDoubleOrNull() ?: 0.7
    private val cacheEnabled = Env.value("FISH_TTS_CACHE")?.lowercase() == "true"
    private val cacheDirectory = Env.path("TTS_CACHE_DIR", "cache/tts")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun generateHostScript(
        segmentTitle: String,
        tracks: List<Track>,
        context: RecommendationContext,
        chapterIndex: Int = 0
    ): String {
        val lead = tracks.firstOrNull()
        val clock = OffsetDateTime.now()
        val hour = clock.hour
        val minute = clock.minute
        val timeText = "%d:%02d".format(hour, minute)
        val mood = hostMoodLabel(context)
        val leadLine = lead?.let { "Now, ${it.artist}, ${it.title}." } ?: "Let this chapter take its time."
        val leadIntro = lead?.let { "${it.title} by ${it.artist}" } ?: "this next record"
        val weather = context.weather?.let {
            " Outside in ${friendlyPlaceName(it.locationName)}, it is ${it.condition} and ${"%.0f".format(it.temperatureC)} degrees."
        }.orEmpty()
        val seed = listOf(segmentTitle, context.variationSeed, lead?.id).joinToString("|").hashCode()
        val firstChapterOpenings = listOf(
            "It is $timeText now.$weather We start quietly, with $leadIntro close enough to feel personal and far enough away to leave some room.",
            "$timeText.$weather This first chapter does not need a grand entrance. $leadIntro can come in like someone lowering their voice at the end of the day.",
            "The hour is $timeText.$weather For the first record, we keep the lights low and let $leadIntro set the pace without announcing itself too hard.",
            "${weather.trimStart()} The clock says $timeText, and this opening chapter is more about settling than declaring. $leadIntro gives us that first soft edge."
        )
        return when (chapterIndex) {
            0 -> "${firstChapterOpenings.randomBy(seed)} It keeps close to $mood without forcing the feeling into shape. $leadLine"
            1 -> listOf(
                "A few songs in, the night has changed texture. This chapter stays with what remains after the noise falls away. $leadIntro gives that feeling a center, then the next songs get to move cleanly around it. $leadLine",
                "We turn a little, but we do not break the spell. $leadIntro keeps the room low and steady, the kind of song that lets memory be present without making a speech out of it. $leadLine",
                "The second chapter should feel less like a reset and more like a handoff. $leadIntro carries the thread forward, soft at the edges and clear in the middle. $leadLine"
            ).randomBy(seed)
            2 -> listOf(
                "This is where the room gets a little wider. Not brighter exactly, just less closed in. $leadIntro gives the chapter more air while keeping the late-night pulse intact. $leadLine",
                "Now we let the show breathe out. $leadIntro opens a wider lane, still careful, still close, but no longer holding every thought in place. $leadLine",
                "The middle has done its quiet work, so this chapter can lift without rushing. $leadIntro is the door opening a little farther. $leadLine"
            ).randomBy(seed)
            else -> listOf(
                "For the last chapter, we do not need to explain too much. The point is to leave the night somewhere softer than where it began, and $leadIntro feels right for that. $leadLine",
                "We take the final turn without tying a ribbon around it. $leadIntro can carry us out slowly, with enough distance to feel calm and enough warmth to stay near. $leadLine",
                "This last stretch is for letting the room settle. $leadIntro does not demand an answer; it just gives the ending somewhere gentle to land. $leadLine"
            ).randomBy(seed)
        }
    }

    private fun hostMoodLabel(context: RecommendationContext): String {
        val signals = context.recentSignals.joinToString(" ")
        return when {
            "avoid=too-sad" in signals -> "something soft without letting the room get too heavy"
            "catalog=chinese-indie-ok" in signals -> "a low-energy Chinese indie thread"
            "routine=late-night-coding" in signals -> "late-night focus with the edges softened"
            "energy=low" in signals -> "a quiet, low-energy stretch"
            context.mood.isNullOrBlank() -> "that late-night feeling where memory is present, but not loud"
            else -> "the feeling you asked for"
        }
    }

    fun synthesize(script: String): HostVoiceAsset {
        val digest = cacheKey(script)
        if (fishApiKey.isNullOrBlank()) {
            return HostVoiceAsset(script = script, audioUrl = null, cacheKey = digest)
        }

        val extension = if (fishFormat == "wav") "wav" else fishFormat
        val outputFile = if (cacheEnabled) {
            cacheDirectory.resolve("$digest.$extension")
        } else {
            cacheDirectory.resolve("$digest-${System.currentTimeMillis()}.$extension")
        }
        if (cacheEnabled && Files.exists(outputFile)) {
            return HostVoiceAsset(script = script, audioUrl = "/media/tts/${outputFile.fileName}", cacheKey = digest)
        }

        return runCatching {
            Files.createDirectories(cacheDirectory)
            val response = httpClient.send(buildFishRequest(script), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() in 200..299) {
                Files.write(
                    outputFile,
                    response.body(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                )
                HostVoiceAsset(script = script, audioUrl = "/media/tts/${outputFile.fileName}", cacheKey = digest)
            } else {
                val reason = response.body().decodeToString().take(220)
                System.err.println("Fish TTS skipped: HTTP ${response.statusCode()} $reason")
                HostVoiceAsset(script = script, audioUrl = null, cacheKey = digest)
            }
        }.getOrElse { error ->
            System.err.println("Fish TTS skipped: ${error.message}")
            HostVoiceAsset(script = script, audioUrl = null, cacheKey = digest)
        }
    }

    private fun cacheKey(script: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$fishModel|${fishVoiceId.orEmpty()}|$fishFormat|$script".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)

    private fun buildFishRequest(script: String): HttpRequest {
        val payload = buildJsonObject {
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

        return HttpRequest.newBuilder(URI.create(fishEndpoint))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer $fishApiKey")
            .header("Content-Type", "application/json")
            .header("model", fishModel)
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
    }
}

class ShowPlanner(
    private val hostConfig: HostConfig,
    private val hostVoiceService: HostVoiceService = HostVoiceService()
) {
    fun plan(tracks: List<Track>, context: RecommendationContext): ShowPlan {
        val safeTracks = tracks.ifEmpty { MockMusicProviderFallback.tracks }
        val today = LocalDate.now()
        val title = chooseTitle(context, today)
        val chunks = safeTracks.chunked(3).filter { it.size == 3 }.take(4).ifEmpty { listOf(safeTracks.take(3)) }
        val segments = chunks.mapIndexed { index, segmentTracks ->
            val segmentTitle = when (index) {
                0 -> "Chapter One - Opening the Room"
                1 -> "Chapter Two - A Softer Middle"
                2 -> "Chapter Three - Bigger Room, Bigger Chorus"
                else -> "Chapter Four - Leaving a Light On"
            }
            val script = hostVoiceService.generateHostScript(segmentTitle, segmentTracks, context, index)
            ShowSegment(
                id = "seg-${today}-$index",
                title = segmentTitle,
                hostScript = script,
                tracks = segmentTracks
            )
        }
        return ShowPlan(
            id = "show-${today}-${System.currentTimeMillis()}",
            title = title,
            generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            hostConfig = hostConfig,
            segments = segments
        )
    }

    private fun chooseTitle(context: RecommendationContext, today: LocalDate): String {
        val weekday = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val mood = context.mood.orEmpty().lowercase()
        return when {
            "commute" in mood -> "Slow Cache Before Commute"
            "rain" in mood -> "Rain at the Edge of Sleep"
            "coding" in mood -> "$weekday Night Compile"
            "sad" in mood -> "A Little Less Heavy"
            else -> "$weekday Night Exhale"
        }
    }
}

class RadioAgent {
    fun buildContext(mood: String?, hostConfig: HostConfig): RecommendationContext {
        val cleanedMood = mood?.trim()?.takeIf { it.isNotBlank() }
        val lower = cleanedMood.orEmpty().lowercase()
        val signals = buildList {
            add("host=${hostConfig.hostName}")
            add("language=${hostConfig.hostLanguage}")
            add("speech=${hostConfig.segmentSpeechMode}")
            if ("coding" in lower) add("routine=late-night-coding")
            if ("quiet" in lower || "soft" in lower || "not too energetic" in lower) add("energy=low")
            if ("english" in lower || "英文" in lower) add("catalog=english-only")
            if ("chinese" in lower || "中文" in lower) add("catalog=chinese-indie-ok")
            if ("mandarin" in lower || "国语" in lower || "普通话" in lower) add("catalog=mandarin")
            if ("less sad" in lower) add("avoid=too-sad")
        }
        val intent = when {
            cleanedMood == null -> "daily_show"
            "less sad" in lower -> "retune_mood"
            "play" in lower || "something" in lower || "song" in lower || "music" in lower -> "mood_request"
            else -> "conversation_tuning"
        }
        return RecommendationContext(
            mood = cleanedMood,
            localTime = OffsetDateTime.now().toString(),
            hostLanguage = hostConfig.hostLanguage,
            intent = intent,
            recentSignals = signals,
            variationSeed = "${System.currentTimeMillis()}-${Random.nextInt()}"
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
        return AgentTrace(
            mode = plannerMode,
            summary = rationale?.takeIf { it.isNotBlank() }
                ?: "I read the request as ${context.intent.replace("_", " ")} and built ${plan.segments.size} segments, keeping the host between song groups instead of interrupting every track.",
            contextWindow = listOf(
                "taste profile: prototype fallback shelf",
                candidateSelection?.let { "taste source: ${it.profile.source}" } ?: "taste source: provider fallback",
                candidateSelection?.let { "matched tags: ${it.desiredTags.joinToString(", ")}" } ?: "matched tags: none",
                "routine: ${context.mood ?: "daily late-night listening"}",
                "time: ${context.localTime ?: "now"}",
                context.weather?.let { "weather: ${it.locationName}, ${it.condition}, ${"%.0f".format(it.temperatureC)}C" },
                "host: ${context.hostLanguage}, ${plan.hostConfig.hostStyle}",
                "provider candidates: ${tracks.size} normalized tracks"
            ).filterNotNull(),
            routing = listOf(
                "chat/request -> RadioAgent",
                "RadioAgent -> TasteProfileRepository",
                "TasteProfileRepository -> CandidateSelector",
                "CandidateSelector -> LLM planner or fallback ShowPlanner",
                "empty taste profile -> MusicProvider.getRecommendations(context)",
                "candidate tracks -> ShowPlanner",
                "segments -> PlaybackQueue"
            ),
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
}

class PlaybackQueue(
    private val hostConfig: HostConfig,
    private val hostVoiceService: HostVoiceService = HostVoiceService()
) {
    private var showPlan: ShowPlan? = null
    private var items: List<QueueItem> = emptyList()
    private var currentIndex: Int = 0
    private var isPlaying: Boolean = false

    fun load(plan: ShowPlan) {
        showPlan = plan
        currentIndex = 0
        isPlaying = false
        items = plan.segments.flatMap { segment ->
            val hostAsset = hostVoiceService.synthesize(segment.hostScript)
            val leadTrack = segment.tracks.firstOrNull()
            listOf(
                QueueItem(
                    id = "host-${segment.id}",
                    type = "host_voice",
                    segmentId = segment.id,
                    segmentTitle = segment.title,
                    hostName = hostConfig.hostName,
                    hostLanguage = hostConfig.hostLanguage,
                    hostScript = segment.hostScript,
                    ttsUrl = hostAsset.audioUrl,
                    ttsCacheKey = hostAsset.cacheKey,
                    track = leadTrack
                )
            ) + segment.tracks.drop(1).map { track ->
                QueueItem(
                    id = "track-${segment.id}-${track.provider}-${track.id}",
                    type = "track",
                    segmentId = segment.id,
                    segmentTitle = segment.title,
                    track = track
                )
            }
        }
    }

    fun play(): PlaybackState {
        isPlaying = true
        return state()
    }

    fun pause(): PlaybackState {
        isPlaying = false
        return state()
    }

    fun next(): PlaybackState {
        if (items.isNotEmpty()) currentIndex = min(currentIndex + 1, items.lastIndex)
        return state()
    }

    fun previous(): PlaybackState {
        if (items.isNotEmpty()) currentIndex = (currentIndex - 1).coerceAtLeast(0)
        return state()
    }

    fun clear(): PlaybackState {
        showPlan = null
        items = emptyList()
        currentIndex = 0
        isPlaying = false
        return state()
    }

    fun restore(playback: PlaybackState, plan: ShowPlan?) {
        if (plan != null) {
            showPlan = plan
            items = playback.queue
            currentIndex = playback.currentIndex.coerceIn(0, playback.queue.lastIndex.coerceAtLeast(0))
            isPlaying = playback.isPlaying
        }
    }

    fun state(): PlaybackState {
        val current = items.getOrNull(currentIndex)
        return PlaybackState(
            currentItem = current,
            currentIndex = currentIndex,
            queue = items,
            showTitle = showPlan?.title,
            segmentTitle = current?.segmentTitle,
            isPlaying = isPlaying,
            progressMs = 0,
            durationMs = current?.track?.durationMs,
            hostLanguage = hostConfig.hostLanguage
        )
    }
}

class RadioEngine(
    private val provider: MusicProvider,
    private val importProvider: MusicProvider,
    private val hostConfig: HostConfig,
    private val store: StateStore,
    private val candidateSelector: CandidateSelector = CandidateSelector(TasteProfileRepository()),
    private val playlistImportService: PlaylistImportService = PlaylistImportService(),
    private val weatherService: WeatherService = WeatherService()
) {
    private val agent = RadioAgent()
    private val planner = ShowPlanner(hostConfig)
    private val llmPlanner = ConfiguredLlmShowPlanner.fromEnvironment()
    private val queue = PlaybackQueue(hostConfig)
    private var activePlan: ShowPlan? = null
    private var settings: UserSettings = UserSettings()

    init {
        val restored = store.load()
        activePlan = restored.showPlan
        settings = restored.settings
        queue.restore(restored.playback, restored.showPlan)
    }

    suspend fun planToday(mood: String? = null): PlanResponse {
        refreshWeatherForPlanning()
        val context = withWeather(agent.buildContext(mood, hostConfig))
        val candidateSelection = candidateSelector.select(context)
        val tracks = if (candidateSelection.tracks.isNotEmpty()) {
            candidateSelection.tracks.map { it.toTrack() }
        } else {
            provider.getRecommendations(context)
        }
        val llmPlan = llmPlanner.plan(
            context = context,
            hostConfig = hostConfig,
            candidates = tracks,
            tasteProfile = candidateSelection.profile,
            taggedCandidates = candidateSelection.tracks
        )
        activePlan = hydratePlanStreams(llmPlan?.toShowPlan(tracks, hostConfig) ?: planner.plan(tracks, context))
        queue.load(activePlan!!)
        persist()
        val plannerMode = if (llmPlan == null) "mock-radio-agent" else llmPlanner.mode
        return PlanResponse(
            activePlan!!,
            queue.state(),
            agent.trace(
                context = context,
                tracks = tracks,
                plan = activePlan!!,
                plannerMode = plannerMode,
                rationale = llmPlan?.rationale,
                candidateSelection = candidateSelection.takeIf { it.tracks.isNotEmpty() }
            )
        )
    }

    fun now(): PlaybackState = queue.state()

    fun play(): PlaybackState = queue.play().also { persist() }

    fun pause(): PlaybackState = queue.pause().also { persist() }

    fun next(): PlaybackState = queue.next().also { persist() }

    fun previous(): PlaybackState = queue.previous().also { persist() }

    fun clearPlayback(): PlaybackState {
        activePlan = null
        return queue.clear().also { persist() }
    }

    fun settings(): SettingsResponse =
        SettingsResponse(weatherLocation = settings.weatherLocation, weather = settings.weather)

    suspend fun setWeatherLocation(location: String): SettingsResponse {
        val cleaned = location.trim().takeIf { it.isNotBlank() }
        settings = settings.copy(weatherLocation = cleaned)
        if (cleaned != null) {
            weatherService.refresh(cleaned)?.let { snapshot ->
                settings = settings.copy(weather = snapshot)
            }
        }
        persist()
        return settings()
    }

    suspend fun refreshWeather(): SettingsResponse {
        refreshWeatherForPlanning()
        persist()
        return settings()
    }

    suspend fun playlist(id: String): Playlist = provider.getPlaylist(id)

    suspend fun importPlaylist(source: String): ImportPlaylistResponse {
        val id = extractPlaylistId(source)
        val playlist = importProvider.getPlaylist(id)
        return playlistImportService.save(source = source, playlist = playlist)
    }

    private fun persist() {
        store.save(StoredState(showPlan = activePlan, playback = queue.state(), settings = settings))
    }

    private suspend fun refreshWeatherForPlanning() {
        val location = settings.weatherLocation?.takeIf { it.isNotBlank() } ?: return
        weatherService.refresh(location)?.let { snapshot ->
            settings = settings.copy(weather = snapshot)
        }
    }

    private fun withWeather(context: RecommendationContext): RecommendationContext {
        val weather = settings.weather ?: return context
        val summary = weatherSummary(weather)
        return context.copy(
            weather = weather,
            recentSignals = context.recentSignals + listOf("weather=$summary")
        )
    }

    private fun weatherSummary(weather: WeatherSnapshot): String {
        val feels = weather.apparentTemperatureC?.let { ", feels ${"%.0f".format(it)}C" }.orEmpty()
        val rain = weather.precipitationMm?.takeIf { it > 0.0 }?.let { ", ${"%.1f".format(it)}mm rain" }.orEmpty()
        val wind = weather.windSpeedKmh?.let { ", wind ${"%.0f".format(it)}km/h" }.orEmpty()
        return "${weather.locationName}: ${weather.condition}, ${"%.0f".format(weather.temperatureC)}C$feels$rain$wind"
    }

    private suspend fun hydrateStreams(tracks: List<Track>): List<Track> =
        provider.getStreamUrls(tracks.map { it.id })
            .associateBy { it.trackId }
            .let { streams ->
                tracks.map { track ->
                    val stream = streams[track.id] ?: StreamUrl(provider.name, track.id, url = null, reason = "unknown")
            track.copy(streamUrl = stream.url, unavailableReason = stream.reason.takeIf { stream.url == null })
                }
        }

    private suspend fun hydratePlanStreams(plan: ShowPlan): ShowPlan =
        plan.copy(
            segments = plan.segments.map { segment ->
                segment.copy(tracks = hydrateStreams(segment.tracks))
            }
        )
}

fun extractPlaylistId(source: String): String {
    val idMatch = Regex("""(?:id=|playlist/)(\d+)""").find(source)
    return idMatch?.groupValues?.getOrNull(1) ?: source.trim()
}

private fun LlmShowPlan.toShowPlan(candidates: List<Track>, hostConfig: HostConfig): ShowPlan? {
    val trackById = candidates.associateBy { it.id }
    val today = LocalDate.now()
    val showSegments = segments.mapIndexedNotNull { index, segment ->
        val segmentTracks = segment.trackIds.mapNotNull { trackById[it] }.take(3)
        if (segmentTracks.size < 3) {
            null
        } else {
            ShowSegment(
                id = "seg-${today}-llm-$index",
                title = segment.title.take(80).ifBlank { "Segment ${index + 1}" },
                hostScript = segment.hostScript.take(900),
                tracks = segmentTracks
            )
        }
    }
    if (showSegments.size < 2) return null
    return ShowPlan(
        id = "show-${today}-llm-${System.currentTimeMillis()}",
        title = title.take(80).ifBlank { "Aftertaste Session" },
        generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        hostConfig = hostConfig,
        segments = showSegments
    )
}

private object MockMusicProviderFallback {
    val tracks = listOf(
        Track("mock", "fallback-1", "Late Signal", "Aftertaste House Band"),
        Track("mock", "fallback-2", "No Rush", "Aftertaste House Band"),
        Track("mock", "fallback-3", "Almost Morning", "Aftertaste House Band")
    )
}

private fun <T> List<T>.randomBy(seed: Int): T =
    this[Random(seed).nextInt(size)]
