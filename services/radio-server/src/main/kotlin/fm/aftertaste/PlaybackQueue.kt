package fm.aftertaste

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.math.min

class PlaybackQueue(
    private val hostConfig: HostConfig,
    private val hostVoiceService: HostVoiceService
) {
    private var showPlan: ShowPlan? = null
    private var items: List<QueueItem> = emptyList()
    private var currentIndex: Int = 0
    private var isPlaying: Boolean = false

    suspend fun load(plan: ShowPlan) = coroutineScope {
        // The plan carries the host config that planning actually used (incl. the runtime
        // language override); voice synthesis and queue metadata follow it, not the ctor default.
        val planHost = plan.hostConfig
        val hostAssets = plan.segments
            .map { segment -> async { hostVoiceService.synthesize(segment.hostScript, planHost.hostLanguage) } }
            .awaitAll()

        showPlan = plan
        currentIndex = 0
        isPlaying = false
        items = plan.segments.flatMapIndexed { segmentIndex, segment ->
            val hostAsset = hostAssets[segmentIndex]
            val leadTrack = segment.tracks.firstOrNull()
            listOf(
                QueueItem(
                    id = "host-${segment.id}",
                    type = "host_voice",
                    segmentId = segment.id,
                    segmentTitle = segment.title,
                    hostName = planHost.hostName,
                    hostLanguage = planHost.hostLanguage,
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

    fun currentItem(): QueueItem? = items.getOrNull(currentIndex)

    /**
     * Swaps the track on the item now playing — used to drop a freshly re-fetched stream URL
     * into place. A `host_voice` row carries its chapter lead in the same `track` field, so
     * this covers both row types.
     */
    fun updateCurrentTrack(track: Track) {
        val existing = items.getOrNull(currentIndex) ?: return
        items = items.toMutableList().also { it[currentIndex] = existing.copy(track = track) }
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
            hostLanguage = showPlan?.hostConfig?.hostLanguage ?: hostConfig.hostLanguage
        )
    }
}
