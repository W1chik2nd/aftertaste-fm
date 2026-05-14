package fm.aftertaste

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val MAX_DETERMINISTIC_SEGMENTS = 4

class ShowPlanner(
    private val hostConfig: HostConfig
) {
    fun plan(tracks: List<Track>, context: RecommendationContext, activeHostConfig: HostConfig = hostConfig): ShowPlan {
        val today = LocalDate.now()
        val weekday = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val chunks = tracks
            .chunked(SEGMENT_TRACK_COUNT)
            .filter { it.size == SEGMENT_TRACK_COUNT }
            .take(MAX_DETERMINISTIC_SEGMENTS)
        val segments = chunks.mapIndexed { index, segmentTracks ->
            val segmentTitle = "Chapter ${index + 1}"
            val script = HostScriptTemplates.generate(
                segmentTitle, segmentTracks, context, index, activeHostConfig.hostLanguage
            )
            ShowSegment(
                id = "seg-${today}-$index",
                title = segmentTitle,
                hostScript = script,
                tracks = segmentTracks
            )
        }
        return ShowPlan(
            id = "show-${today}-${System.currentTimeMillis()}",
            title = "Aftertaste · $weekday",
            generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            hostConfig = activeHostConfig,
            segments = segments
        )
    }
}
