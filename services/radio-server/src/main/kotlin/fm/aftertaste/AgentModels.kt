package fm.aftertaste

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class AgentChatResponse(
    val message: String,
    val mode: String,
    val shouldPlan: Boolean = false,
    val command: String? = null
)

@Serializable
data class AgentSignal(
    val label: String,
    val value: String
)

@Serializable
data class AgentTrace(
    val mode: String,
    val summary: String,
    val contextWindow: List<String>,
    val routing: List<String>,
    val recommendationStrategy: List<String>,
    val signals: List<AgentSignal>
)

@Serializable
data class LlmPlanSegment(
    val title: String,
    val hostScript: String,
    val trackIds: List<String>
)

@Serializable
data class LlmShowPlan(
    val title: String,
    val rationale: String,
    val segments: List<LlmPlanSegment>
)

@Serializable
data class PlanResponse(
    val showPlan: ShowPlan,
    val playback: PlaybackState,
    val agentTrace: AgentTrace? = null
)

@Serializable
data class HostVoiceAsset(
    val script: String,
    val audioUrl: String? = null,
    val cacheKey: String
)
