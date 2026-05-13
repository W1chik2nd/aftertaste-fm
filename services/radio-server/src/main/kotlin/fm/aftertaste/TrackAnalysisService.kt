package fm.aftertaste

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

private const val TRACK_ANALYSIS_MAX_TOKENS = 2200
private const val ANALYSIS_TAG_LIMIT = 12
private const val ANALYSIS_TAG_MAX_CHARS = 60
private const val ANALYSIS_NOTE_MAX_CHARS = 500
private const val ANALYSIS_ERROR_NOTE_CHARS = 240

class TrackAnalysisException(message: String) : RuntimeException(message)

class TrackAnalysisService(
    private val config: LlmRuntimeConfig,
    private val json: Json = HttpClients.sharedJson
) {
    val model: String = config.model
    private val completionClient = LlmCompletionClient(config, json)

    suspend fun analyze(
        track: TaggedTrack,
        lyric: String?,
        playlistName: String
    ): EvidenceTrackAnalysis {
        val output = completionClient.complete(
            systemPrompt(),
            userPrompt(track, lyric, playlistName),
            maxTokens = TRACK_ANALYSIS_MAX_TOKENS,
            jsonMode = true,
            responseSchema = null,
            cacheSystemPrompt = true
        ) ?: throw TrackAnalysisException("LLM response did not contain output text.")
        val analysis = try {
            json.decodeFromString<LlmTrackAnalysis>(output)
        } catch (cause: SerializationException) {
            throw TrackAnalysisException("LLM response was not valid track analysis JSON: ${cause.message}")
        }
        return track.toEvidence(analysis, lyric, modelEvidence = true, notes = analysis.notes)
    }

    fun failureEvidence(track: TaggedTrack, lyric: String?, message: String): EvidenceTrackAnalysis =
        track.toEvidence(
            LlmTrackAnalysis(needsReview = true),
            lyric,
            modelEvidence = false,
            notes = "Analysis failed: ${message.take(ANALYSIS_ERROR_NOTE_CHARS)}"
        )

    fun estimatedCostUsd(trackCount: Int): Double? = null

    private fun userPrompt(track: TaggedTrack, lyric: String?, playlistName: String): String =
        json.encodeToString(
            AnalysisPrompt(
                playlistName = playlistName,
                track = AnalysisTrackInput(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    provider = track.provider,
                    metadataLanguage = track.language,
                    lyrics = lyric
                )
            )
        )

    private fun systemPrompt(): String =
        """
        You are Aftertaste FM's offline music analyst.
        Analyze one track for a private AI radio using a general taxonomy, not a taxonomy tailored to one playlist.
        You may use title, artist, album, provider metadata, playlist context, and lyrics.
        You do not have audio. Be conservative about sonic features unless metadata or lyrics clearly support them.
        Prefer unknown or low confidence over pretending.
        Do not quote lyrics. Do not write long prose. Use lowercase kebab-case tags.
        Evidence strings must come from: metadata, lyrics, playlist_context, model_inference.
        Set needsReview true if lyrics are missing, language is uncertain, score confidence is low, or evidence is ambiguous.
        Return JSON only with language, moodTags, contextTags, soundTags, useTags, scores, notes, and needsReview.
        """.trimIndent()

    companion object {
        fun fromEnvironment(): TrackAnalysisService? =
            LlmRuntimeConfig.fromEnvironment(LlmUseCase.Planner)?.let { TrackAnalysisService(it) }
    }
}

@Serializable
private data class AnalysisPrompt(
    val playlistName: String,
    val track: AnalysisTrackInput
)

@Serializable
private data class AnalysisTrackInput(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val provider: String,
    val metadataLanguage: String,
    val lyrics: String?
)

@Serializable
private data class LlmTrackAnalysis(
    val language: EvidenceValueString? = null,
    val moodTags: List<EvidenceTag> = emptyList(),
    val contextTags: List<EvidenceTag> = emptyList(),
    val soundTags: List<EvidenceTag> = emptyList(),
    val useTags: List<EvidenceTag> = emptyList(),
    val scores: EvidenceScores = EvidenceScores(),
    val notes: String? = null,
    val needsReview: Boolean = true
)

private fun TaggedTrack.toEvidence(
    analysis: LlmTrackAnalysis,
    lyric: String?,
    modelEvidence: Boolean,
    notes: String?
): EvidenceTrackAnalysis =
    EvidenceTrackAnalysis(
        provider = provider,
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        language = analysis.language?.cleanStringValue() ?: EvidenceValueString(language, 0.25, listOf("metadata")),
        moodTags = analysis.moodTags.cleanTags(),
        contextTags = analysis.contextTags.cleanTags(),
        soundTags = analysis.soundTags.cleanTags(),
        useTags = analysis.useTags.cleanTags(),
        scores = analysis.scores.cleanScores(),
        evidence = TrackEvidenceState(metadata = true, lyrics = lyric != null, model = modelEvidence),
        notes = notes?.take(ANALYSIS_NOTE_MAX_CHARS),
        needsReview = analysis.needsReview || !modelEvidence,
        lastAnalyzedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    )

private fun EvidenceValueString.cleanStringValue(): EvidenceValueString =
    EvidenceValueString(value.takeIf { it.isNotBlank() } ?: "unknown", confidence.clamped(), evidence.cleanEvidence())

private fun EvidenceScores.cleanScores(): EvidenceScores =
    EvidenceScores(
        energy = energy.cleanNumber(0.5),
        valence = valence.cleanNumber(0.5),
        night = night.cleanNumber(0.5),
        coding = coding.cleanNumber(0.5),
        skipRisk = skipRisk.cleanNumber(0.2),
        danceability = danceability.cleanNumber(0.5),
        acousticness = acousticness.cleanNumber(0.5),
        lyricDensity = lyricDensity.cleanNumber(0.5),
        vocalPresence = vocalPresence.cleanNumber(0.5),
        familiarity = familiarity.cleanNumber(0.5),
        intensity = intensity.cleanNumber(0.5)
    )

private fun EvidenceValueDouble.cleanNumber(default: Double): EvidenceValueDouble =
    EvidenceValueDouble(value.clamped(default), confidence.clamped(), evidence.cleanEvidence())

private fun List<EvidenceTag>.cleanTags(): List<EvidenceTag> =
    filter { it.tag.isNotBlank() }
        .map {
            EvidenceTag(
                tag = safeFileStem(it.tag).take(ANALYSIS_TAG_MAX_CHARS),
                confidence = it.confidence.clamped(),
                evidence = it.evidence.cleanEvidence()
            )
        }
        .filter { it.tag.isNotBlank() }
        .distinctBy { it.tag }
        .take(ANALYSIS_TAG_LIMIT)

private fun List<String>.cleanEvidence(): List<String> {
    val allowed = setOf("metadata", "lyrics", "playlist_context", "model_inference")
    return filter { it in allowed }.distinct()
}

private fun Double.clamped(default: Double = 0.0): Double =
    if (isFinite()) min(1.0, max(0.0, this)) else default
