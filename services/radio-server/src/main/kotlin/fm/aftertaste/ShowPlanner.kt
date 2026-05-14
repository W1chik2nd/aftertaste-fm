package fm.aftertaste

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val MAX_DETERMINISTIC_SEGMENTS = 4

class ShowPlanner(
    private val hostConfig: HostConfig
) {
    fun plan(tracks: List<Track>, activeHostConfig: HostConfig = hostConfig): ShowPlan {
        val today = LocalDate.now()
        val chunks = tracks
            .chunked(SEGMENT_TRACK_COUNT)
            .filter { it.size == SEGMENT_TRACK_COUNT }
            .take(MAX_DETERMINISTIC_SEGMENTS)
        val segments = chunks.mapIndexed { index, segmentTracks ->
            ShowSegment(
                id = "seg-${today}-$index",
                title = "",
                hostScript = "",
                tracks = segmentTracks
            )
        }
        return ShowPlan(
            id = "show-${today}-${System.currentTimeMillis()}",
            title = "",
            generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            hostConfig = activeHostConfig,
            segments = segments
        )
    }
}
