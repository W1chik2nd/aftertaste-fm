package fm.aftertaste

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RadioEngine(
    private val provider: MusicProvider,
    private val importProvider: MusicProvider,
    private val hostConfig: HostConfig,
    private val store: StateStore,
    private val tasteRepository: TasteProfileRepository = TasteProfileRepository(),
    private val candidateSelector: CandidateSelector = CandidateSelector(tasteRepository),
    private val playlistImportService: PlaylistImportService = PlaylistImportService(),
    private val weatherService: WeatherService = WeatherService(),
    hostVoiceService: HostVoiceService = HostVoiceService()
) {
    private val agent = RadioAgent()
    private val planner = ShowPlanner(hostConfig, hostVoiceService)
    private val llmPlanner = ConfiguredLlmShowPlanner.fromEnvironment()
    private val queue = PlaybackQueue(hostConfig, hostVoiceService)
    private val mutex = Mutex()
    private var activePlan: ShowPlan? = null
    private var settings: UserSettings = UserSettings()

    init {
        val restored = store.loadBlocking()
        activePlan = restored.showPlan
        settings = restored.settings
        queue.restore(restored.playback, restored.showPlan)
    }

    suspend fun planToday(mood: String? = null, routingIntent: RoutingIntent? = null): PlanResponse = mutex.withLock {
        refreshWeatherForPlanning()
        val tasteProfile = tasteRepository.load()
        val catalogArtists = tasteProfile.tracks.map { it.artist }.distinct()
        val baseContext = agent.buildContext(mood, hostConfig, tasteProfile.rules, catalogArtists, routingIntent)
        val context = withMemory(withWeather(baseContext))
        val stationHostConfig = hostConfig.copy(hostStyle = context.stationStyle?.hostStyle ?: hostConfig.hostStyle)
        store.rememberMessage("user", mood ?: "Generate today's show.")
        val candidateSelection = candidateSelector.select(context, tasteProfile)
        val tracks = if (candidateSelection.tracks.isNotEmpty()) {
            candidateSelection.tracks.map { it.toTrack() }
        } else {
            provider.getRecommendations(context)
        }
        val llmPlan = llmPlanner.plan(
            context = context,
            hostConfig = stationHostConfig,
            candidates = tracks,
            tasteProfile = candidateSelection.profile,
            taggedCandidates = candidateSelection.tracks
        )
        activePlan = hydratePlanStreams(llmPlan?.toShowPlan(tracks, stationHostConfig) ?: planner.plan(tracks, context, stationHostConfig))
        queue.load(activePlan!!)
        store.rememberPlan(activePlan!!)
        persist()
        val plannerMode = if (llmPlan == null) "mock-radio-agent" else llmPlanner.mode
        PlanResponse(
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

    suspend fun now(): PlaybackState = mutex.withLock { queue.state() }

    suspend fun play(): PlaybackState = mutex.withLock {
        queue.play().also {
            store.rememberPlayback("play", it.currentItem)
            persist()
        }
    }

    suspend fun pause(): PlaybackState = mutex.withLock {
        queue.pause().also {
            store.rememberPlayback("pause", it.currentItem)
            persist()
        }
    }

    suspend fun next(): PlaybackState = mutex.withLock {
        queue.next().also {
            store.rememberPlayback("next", it.currentItem)
            persist()
        }
    }

    suspend fun previous(): PlaybackState = mutex.withLock {
        queue.previous().also {
            store.rememberPlayback("previous", it.currentItem)
            persist()
        }
    }

    suspend fun handleCommand(command: String): PlaybackState? =
        when (command) {
            "next" -> next()
            "previous" -> previous()
            "pause" -> pause()
            "play" -> play()
            else -> null
        }

    suspend fun clearPlayback(): PlaybackState = mutex.withLock {
        activePlan = null
        queue.clear().also { persist() }
    }

    suspend fun rememberMessage(role: String, content: String) {
        store.rememberMessage(role, content)
    }

    fun settings(): SettingsResponse =
        SettingsResponse(
            weatherLocation = settings.weatherLocation,
            weather = settings.weather,
            integrations = IntegrationStatuses.current()
        )

    suspend fun setWeatherLocation(location: String): SettingsResponse = mutex.withLock {
        val cleaned = location.trim().takeIf { it.isNotBlank() }
        settings = settings.copy(weatherLocation = cleaned)
        if (cleaned != null) {
            weatherService.refresh(cleaned)?.let { snapshot ->
                settings = settings.copy(weather = snapshot)
            }
        }
        persist()
        settings()
    }

    suspend fun refreshWeather(): SettingsResponse = mutex.withLock {
        refreshWeatherForPlanning()
        persist()
        settings()
    }

    suspend fun playlist(id: String): Playlist = provider.getPlaylist(id)

    suspend fun lyrics(trackId: String): LyricsResponse =
        LyricsResponse(provider = provider.name, trackId = trackId, lyrics = provider.getLyrics(trackId))

    suspend fun importPlaylist(source: String): ImportPlaylistResponse {
        val id = extractPlaylistId(source)
        val playlist = importProvider.getPlaylist(id)
        val lyricsByTrackId = fetchImportLyrics(playlist)
        return playlistImportService.save(source = source, playlist = playlist, lyricsByTrackId = lyricsByTrackId)
    }

    private suspend fun fetchImportLyrics(playlist: Playlist): Map<String, String?> {
        val lyrics = linkedMapOf<String, String?>()
        for (track in playlist.tracks) {
            lyrics[track.id] = importProvider.getLyrics(track.id)?.trim()?.takeIf { it.isNotBlank() }
        }
        return lyrics
    }

    private suspend fun persist() {
        store.save(StoredState(showPlan = activePlan, playback = queue.state(), settings = settings))
    }

    private suspend fun refreshWeatherForPlanning() {
        val location = settings.weatherLocation?.takeIf { it.isNotBlank() } ?: return
        weatherService.refresh(location)?.let { snapshot ->
            settings = settings.copy(weather = snapshot)
        }
    }

    private fun withWeather(context: RecommendationContext): RecommendationContext =
        settings.weather?.let { context.copy(weather = it) } ?: context

    private suspend fun withMemory(context: RecommendationContext): RecommendationContext =
        context.copy(memory = store.recentMemory())

    private suspend fun hydrateStreams(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        val streams = provider.getStreamUrls(tracks.map { it.id }).associateBy { it.trackId }
        return tracks.map { track ->
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

internal fun LlmShowPlan.toShowPlan(candidates: List<Track>, hostConfig: HostConfig): ShowPlan? {
    val trackById = candidates.associateBy { it.id }
    val today = java.time.LocalDate.now()
    val showSegments = segments.mapIndexedNotNull { index, segment ->
        val segmentTracks = segment.trackIds.mapNotNull { trackById[it] }.take(SEGMENT_TRACK_COUNT)
        if (segmentTracks.size < SEGMENT_TRACK_COUNT) {
            null
        } else {
            ShowSegment(
                id = "seg-${today}-llm-$index",
                title = segment.title.ifBlank { "Segment ${index + 1}" },
                hostScript = segment.hostScript,
                tracks = segmentTracks
            )
        }
    }
    if (showSegments.size < MIN_SHOW_SEGMENTS) return null
    return ShowPlan(
        id = "show-${today}-llm-${System.currentTimeMillis()}",
        title = title.ifBlank { "Aftertaste Session" },
        generatedAt = java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        hostConfig = hostConfig,
        segments = showSegments
    )
}
