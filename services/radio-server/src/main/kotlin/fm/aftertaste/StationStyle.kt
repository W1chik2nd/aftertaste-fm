package fm.aftertaste

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class StationStyle(
    val daypart: String,
    val label: String,
    val hostStyle: String,
    val energyTarget: Double,
    val nightWeight: Double,
    val valenceWeight: Double
)

fun stationStyleFor(time: OffsetDateTime): StationStyle =
    when (time.hour) {
        in MORNING_HOURS -> StationStyle(
            daypart = "morning",
            label = "Morning lift",
            hostStyle = "clear morning radio, warm and lightly energized",
            energyTarget = 0.56,
            nightWeight = 0.12,
            valenceWeight = 0.28
        )
        in AFTERNOON_HOURS -> StationStyle(
            daypart = "afternoon",
            label = "Afternoon motion",
            hostStyle = "bright afternoon radio, mobile and conversational",
            energyTarget = 0.62,
            nightWeight = 0.08,
            valenceWeight = 0.22
        )
        in EVENING_HOURS -> StationStyle(
            daypart = "evening",
            label = "Evening city",
            hostStyle = "evening radio with groove, warmth, and city-light momentum",
            energyTarget = 0.52,
            nightWeight = 0.28,
            valenceWeight = 0.14
        )
        in DEEP_NIGHT_HOURS -> StationStyle(
            daypart = "deep-night",
            label = "Deep night",
            hostStyle = "deep-night radio, intimate, sparse, and unhurried",
            energyTarget = 0.28,
            nightWeight = 0.72,
            valenceWeight = 0.0
        )
        else -> StationStyle(
            daypart = "late-night",
            label = "Late night",
            hostStyle = "calm late-night radio",
            energyTarget = 0.4,
            nightWeight = 0.58,
            valenceWeight = 0.04
        )
    }

private val MORNING_HOURS = 6..10
private val AFTERNOON_HOURS = 11..16
private val EVENING_HOURS = 17..20
private val DEEP_NIGHT_HOURS = 0..4
