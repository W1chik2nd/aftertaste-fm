package fm.aftertaste

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val JOB_RUNNING = "running"
private const val JOB_COMPLETED = "completed"
private const val JOB_CANCELLED = "cancelled"
private const val JOB_FAILED = "failed"

class AnalysisUnavailableException(message: String) : RuntimeException(message)
class ImportNotFoundException(message: String) : RuntimeException(message)
class JobNotFoundException(message: String) : RuntimeException(message)

class AnalysisJobService(
    private val imports: PlaylistImportService,
    private val evidence: EvidenceLibraryService,
    private val analyzer: TrackAnalysisService?,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(AnalysisJobService::class.java)
    private val jobs = ConcurrentHashMap<String, AnalysisJobView>()
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()

    suspend fun start(slug: String, request: AnalyzeImportRequest): AnalyzeJobStartResponse {
        val service = analyzer ?: throw AnalysisUnavailableException("LLM_API_KEY is required for analysis.")
        val draft = imports.draft(slug) ?: throw ImportNotFoundException("Import not found: $slug")
        val lyrics = imports.lyrics(slug)?.lyricsByTrackId ?: emptyMap()
        val tracks = tracksToAnalyze(draft, request)
        val jobId = UUID.randomUUID().toString()
        val startedAt = nowIso()
        jobs[jobId] = AnalysisJobView(jobId, JOB_RUNNING, processed = 0, total = tracks.size, startedAt = startedAt)
        cancellations[jobId] = AtomicBoolean(false)
        if (tracks.isEmpty()) {
            jobs[jobId] = jobs[jobId]!!.copy(status = JOB_COMPLETED, finishedAt = nowIso())
        } else {
            scope.launch { runJob(jobId, draft, tracks, lyrics, service) }
        }
        return AnalyzeJobStartResponse(
            jobId = jobId,
            estimatedCalls = tracks.size,
            estimatedCostUsd = service.estimatedCostUsd(tracks.size),
            model = service.model
        )
    }

    fun get(jobId: String): AnalysisJobView =
        jobs[jobId] ?: throw JobNotFoundException("Job not found: $jobId")

    fun cancel(jobId: String): AnalysisJobView {
        val current = jobs[jobId] ?: throw JobNotFoundException("Job not found: $jobId")
        cancellations[jobId]?.set(true)
        val cancelled = current.copy(status = JOB_CANCELLED, finishedAt = current.finishedAt ?: nowIso())
        jobs[jobId] = cancelled
        return cancelled
    }

    fun shutdown() {
        cancellations.values.forEach { it.set(true) }
    }

    private suspend fun tracksToAnalyze(
        draft: TaggedPlaylistDraft,
        request: AnalyzeImportRequest
    ): List<TaggedTrack> {
        val requestedIds = request.trackIds?.toSet()
        val scoped = draft.tracks.filter { requestedIds == null || it.id in requestedIds }
        if (request.force) return scoped
        val existing = evidence.existingKeys()
        return scoped.filterNot { "${it.provider}:${it.id}" in existing }
    }

    private suspend fun runJob(
        jobId: String,
        draft: TaggedPlaylistDraft,
        tracks: List<TaggedTrack>,
        lyrics: Map<String, String?>,
        service: TrackAnalysisService
    ) {
        var wroteAny = false
        try {
            for (track in tracks) {
                if (isCancelled(jobId)) break
                update(jobId) { it.copy(current = track.toSummary()) }
                val result = analyzeOne(track, lyrics[track.id], draft.playlistName, service)
                evidence.save(result.analysis)
                wroteAny = true
                update(jobId) {
                    it.copy(
                        processed = it.processed + 1,
                        current = null,
                        errors = it.errors + result.errorList
                    )
                }
            }
            if (wroteAny) evidence.rebuildAggregate()
            finish(jobId)
        } catch (cancelled: CancellationException) {
            if (wroteAny) runCatchingAggregate()
            throw cancelled
        } catch (cause: IOException) {
            if (wroteAny) runCatchingAggregate()
            fail(jobId, cause.message ?: "File write failed.")
        } catch (cause: SerializationException) {
            if (wroteAny) runCatchingAggregate()
            fail(jobId, cause.message ?: "Evidence serialization failed.")
        }
    }

    private suspend fun runCatchingAggregate() {
        try {
            evidence.rebuildAggregate()
        } catch (cause: IOException) {
            logger.warn("Aggregate rebuild failed after partial run: {}", cause.message)
        }
    }

    private suspend fun analyzeOne(
        track: TaggedTrack,
        lyric: String?,
        playlistName: String,
        service: TrackAnalysisService
    ): TrackAnalysisResult =
        try {
            TrackAnalysisResult(service.analyze(track, lyric, playlistName))
        } catch (cause: TrackAnalysisException) {
            trackFailure(track, lyric, cause.message ?: "Track analysis failed.", service)
        } catch (cause: UpstreamApiException) {
            trackFailure(track, lyric, cause.message ?: "LLM upstream failed.", service)
        }

    private fun trackFailure(
        track: TaggedTrack,
        lyric: String?,
        message: String,
        service: TrackAnalysisService
    ): TrackAnalysisResult {
        logger.warn("Track analysis failed for {}: {}", track.id, message)
        return TrackAnalysisResult(
            analysis = service.failureEvidence(track, lyric, message),
            errorList = listOf(AnalysisJobError(track.id, message))
        )
    }

    private fun finish(jobId: String) {
        val status = if (isCancelled(jobId)) JOB_CANCELLED else JOB_COMPLETED
        update(jobId) { it.copy(status = status, current = null, finishedAt = it.finishedAt ?: nowIso()) }
    }

    private fun fail(jobId: String, message: String) {
        update(jobId) {
            it.copy(
                status = JOB_FAILED,
                current = null,
                errors = it.errors + AnalysisJobError("job", message),
                finishedAt = nowIso()
            )
        }
    }

    private fun update(jobId: String, block: (AnalysisJobView) -> AnalysisJobView) {
        jobs.computeIfPresent(jobId) { _, current -> block(current) }
    }

    private fun isCancelled(jobId: String): Boolean =
        cancellations[jobId]?.get() == true || jobs[jobId]?.status == JOB_CANCELLED

    private fun TaggedTrack.toSummary(): TrackSummary =
        TrackSummary(provider, id, title, artist, album, durationMs, coverUrl)

    private fun nowIso(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

private data class TrackAnalysisResult(
    val analysis: EvidenceTrackAnalysis,
    val errorList: List<AnalysisJobError> = emptyList()
)
