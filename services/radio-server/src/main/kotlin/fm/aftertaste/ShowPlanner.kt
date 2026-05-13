package fm.aftertaste

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class ShowPlanner(
    private val hostConfig: HostConfig,
    private val hostVoiceService: HostVoiceService
) {
    fun plan(tracks: List<Track>, context: RecommendationContext, activeHostConfig: HostConfig = hostConfig): ShowPlan {
        val safeTracks = tracks.ifEmpty { fallbackTracks }
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
            hostConfig = activeHostConfig,
            segments = segments
        )
    }

    private fun chooseTitle(context: RecommendationContext, today: LocalDate): String {
        val weekday = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return when (context.routing.routine) {
            "commute" -> "Slow Cache Before Commute"
            "late-night-coding" -> "$weekday Night Compile"
            else -> when (context.routing.moodTag) {
                "rain" -> "Rain at the Edge of Sleep"
                "less-sad", "sad" -> "A Little Less Heavy"
                else -> "$weekday Night Exhale"
            }
        }
    }

    private val fallbackTracks = listOf(
        Track("mock", "fallback-1", "Late Signal", "Aftertaste House Band"),
        Track("mock", "fallback-2", "No Rush", "Aftertaste House Band"),
        Track("mock", "fallback-3", "Almost Morning", "Aftertaste House Band")
    )
}
