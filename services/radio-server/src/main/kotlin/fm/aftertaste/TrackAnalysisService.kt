package fm.aftertaste

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

private const val TRACK_ANALYSIS_MAX_TOKENS = 2200
private const val ANALYSIS_TAG_LIMIT = 12
private const val ANALYSIS_TAG_MAX_CHARS = 60
private const val ANALYSIS_NOTE_MAX_CHARS = 500
private const val ANALYSIS_EVIDENCE_LIMIT = 8
private const val ANALYSIS_EVIDENCE_MAX_CHARS = 220
private const val ANALYSIS_ERROR_NOTE_CHARS = 240
private const val DEFAULT_ANALYSIS_CONFIDENCE = 0.6

class TrackAnalysisException(message: String) : RuntimeException(message)

class TrackAnalysisService(
    private val config: LlmRuntimeConfig,
    private val json: Json = HttpClients.sharedJson
) {
    val model: String = config.model
    private val completionClient = LlmCompletionClient(config, json)

    suspend fun analyze(track: TaggedTrack, lyric: String?, playlistName: String): EvidenceTrackAnalysis {
        val output = completionClient.complete(
            systemPrompt(),
            userPrompt(track, lyric, playlistName),
            maxTokens = TRACK_ANALYSIS_MAX_TOKENS,
            jsonMode = true,
            responseSchema = trackAnalysisResponseSchema(),
            cacheSystemPrompt = true
        ) ?: throw TrackAnalysisException("LLM response did not contain output text.")
        val analysis = try {
            parseAnalysis(output)
        } catch (cause: IllegalArgumentException) {
            throw TrackAnalysisException("LLM response was not valid track analysis JSON: ${cause.message}")
        }
        return track.toEvidence(analysis, lyric, modelEvidence = true)
    }

    fun failureEvidence(track: TaggedTrack, lyric: String?, message: String): EvidenceTrackAnalysis =
        track.toEvidence(
            LlmTrackAnalysis(
                notes = EvidenceNotes("Analysis failed: ${message.take(ANALYSIS_ERROR_NOTE_CHARS)}"),
                needsReview = true
            ),
            lyric,
            modelEvidence = false
        )

    fun estimatedCostUsd(trackCount: Int): Double? = null

    private fun userPrompt(track: TaggedTrack, lyric: String?, playlistName: String): String =
        json.encodeToString(
            AnalysisPrompt(
                playlistName = playlistName,
                track = AnalysisTrackInput(track.id, track.title, track.artist, track.album, track.durationMs, track.provider, track.language, lyric)
            )
        )

    private fun systemPrompt(): String =
        """
        You are Aftertaste FM's offline music analyst.
        Analyze one track for a private AI radio using a general taxonomy.
        Return JSON only. Required top-level fields: language, moodTags, contextTags, soundTags, useTags, scores, notes, needsReview.
        language must be { "value": string, "confidence": number, "evidence": source[] }.
        Each tag must be { "tag": lowercase-kebab-case string, "confidence": number, "evidence": source[] }.
        Each score must be { "value": 0..1 number, "confidence": number, "evidence": source[] }.
        scores must include energy, valence, night, coding, skipRisk, danceability, acousticness, speechiness, instrumentalness, liveness, emotionalIntensity, lyricalFocus, mainstreamAppeal.
        notes must be { "summary": string, "evidence": [{ "tag": string, "evidenceString": string }] }.
        Evidence source values must be one of metadata, lyrics, playlist_context, model_inference.
        Do not quote lyrics. Evidence strings should paraphrase, not reproduce lyric lines.
        Set needsReview true if lyrics are missing, language is uncertain, score confidence is low, or evidence is ambiguous.
        """.trimIndent()

    private fun parseAnalysis(output: String): LlmTrackAnalysis {
        val root = json.parseToJsonElement(output) as? JsonObject
            ?: throw IllegalArgumentException("Expected a JSON object.")
        val scores = root.obj("scores")
        return LlmTrackAnalysis(
            language = valueString(root["language"], "unknown", "model_inference"),
            moodTags = tags(root["moodTags"]),
            contextTags = tags(root["contextTags"]),
            soundTags = tags(root["soundTags"]),
            useTags = tags(root["useTags"]),
            scores = evidenceScores(scores),
            notes = notes(root["notes"]),
            needsReview = root.boolean("needsReview") ?: true
        )
    }

    companion object {
        fun fromEnvironment(): TrackAnalysisService? =
            LlmRuntimeConfig.fromEnvironment(LlmUseCase.Planner)?.let { TrackAnalysisService(it) }
    }
}

@Serializable
private data class AnalysisPrompt(val playlistName: String, val track: AnalysisTrackInput)

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

private data class LlmTrackAnalysis(
    val language: EvidenceValueString = EvidenceValueString("unknown", 0.0),
    val moodTags: List<EvidenceTag> = emptyList(),
    val contextTags: List<EvidenceTag> = emptyList(),
    val soundTags: List<EvidenceTag> = emptyList(),
    val useTags: List<EvidenceTag> = emptyList(),
    val scores: EvidenceScores = EvidenceScores(),
    val notes: EvidenceNotes? = null,
    val needsReview: Boolean = true
)

private fun TaggedTrack.toEvidence(
    analysis: LlmTrackAnalysis,
    lyric: String?,
    modelEvidence: Boolean
): EvidenceTrackAnalysis =
    EvidenceTrackAnalysis(
        provider = provider,
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        language = analysis.language.cleanStringValue(),
        moodTags = analysis.moodTags.cleanTags(),
        contextTags = analysis.contextTags.cleanTags(),
        soundTags = analysis.soundTags.cleanTags(),
        useTags = analysis.useTags.cleanTags(),
        scores = analysis.scores.cleanScores(),
        evidence = TrackEvidenceState(metadata = true, lyrics = lyric != null, model = modelEvidence),
        notes = analysis.notes?.summary?.take(ANALYSIS_NOTE_MAX_CHARS),
        analysisNotes = analysis.notes?.cleanNotes(),
        needsReview = analysis.needsReview || !modelEvidence,
        lastAnalyzedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    )

private fun evidenceScores(scores: JsonObject?): EvidenceScores {
    val confidence = scores?.number("confidence") ?: DEFAULT_ANALYSIS_CONFIDENCE
    fun score(vararg names: String, default: Double) = scoreValue(scores, names.toList(), default, confidence)
    return EvidenceScores(
        energy = score("energy", default = 0.5),
        valence = score("valence", default = 0.5),
        night = score("night", default = 0.5),
        coding = score("coding", default = 0.5),
        skipRisk = score("skipRisk", "skip-risk", default = 0.2),
        danceability = score("danceability", default = 0.5),
        acousticness = score("acousticness", default = 0.5),
        lyricDensity = score("lyricDensity", "lyric-density", default = 0.5),
        vocalPresence = score("vocalPresence", "vocal-presence", default = 0.5),
        familiarity = score("familiarity", default = 0.5),
        intensity = score("intensity", default = 0.5),
        speechiness = score("speechiness", default = 0.5),
        instrumentalness = score("instrumentalness", default = 0.5),
        liveness = score("liveness", default = 0.5),
        emotionalIntensity = score("emotionalIntensity", "emotional-intensity", default = 0.5),
        lyricalFocus = score("lyricalFocus", "lyrical-focus", default = 0.5),
        mainstreamAppeal = score("mainstreamAppeal", "mainstream-appeal", default = 0.5)
    )
}

private fun scoreValue(scores: JsonObject?, names: List<String>, default: Double, confidence: Double): EvidenceValueDouble {
    val element = names.firstNotNullOfOrNull { scores?.get(it) }
    val obj = element as? JsonObject
    return EvidenceValueDouble(
        value = (obj?.number("value") ?: element?.number() ?: default).clamped(default),
        confidence = (obj?.number("confidence") ?: confidence).clamped(),
        evidence = obj?.stringArray("evidence") ?: listOf("model_inference")
    )
}

private fun valueString(element: JsonElement?, default: String, source: String): EvidenceValueString {
    val obj = element as? JsonObject
    return EvidenceValueString(
        value = obj?.text("value") ?: element?.text() ?: default,
        confidence = (obj?.number("confidence") ?: DEFAULT_ANALYSIS_CONFIDENCE).clamped(),
        evidence = obj?.stringArray("evidence") ?: listOf(source)
    )
}

private fun tags(element: JsonElement?): List<EvidenceTag> =
    (element as? JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? JsonObject
        val tag = obj?.text("tag") ?: obj?.text("name") ?: item.text()
        tag?.let {
            EvidenceTag(
                tag = it,
                confidence = (obj?.number("confidence") ?: DEFAULT_ANALYSIS_CONFIDENCE).clamped(),
                evidence = obj?.stringArray("evidence") ?: listOf("model_inference")
            )
        }
    }

private fun notes(element: JsonElement?): EvidenceNotes? {
    val obj = element as? JsonObject ?: return element?.text()?.let { EvidenceNotes(it) }
    val evidence = (obj["evidence"] as? JsonArray).orEmpty().mapNotNull { item ->
        val detail = item as? JsonObject ?: return@mapNotNull null
        val tag = detail.text("tag") ?: return@mapNotNull null
        val text = detail.text("evidenceString") ?: detail.text("evidence") ?: return@mapNotNull null
        EvidenceDetail(tag, text)
    }
    return EvidenceNotes(obj.text("summary") ?: "", evidence).takeIf { it.summary.isNotBlank() || it.evidence.isNotEmpty() }
}

private fun EvidenceNotes.cleanNotes(): EvidenceNotes =
    EvidenceNotes(
        summary = summary.take(ANALYSIS_NOTE_MAX_CHARS),
        evidence = evidence.filter { it.tag.isNotBlank() && it.evidenceString.isNotBlank() }
            .map { EvidenceDetail(safeFileStem(it.tag).take(ANALYSIS_TAG_MAX_CHARS), it.evidenceString.take(ANALYSIS_EVIDENCE_MAX_CHARS)) }
            .take(ANALYSIS_EVIDENCE_LIMIT)
    )

private fun EvidenceValueString.cleanStringValue(): EvidenceValueString =
    EvidenceValueString(value.takeIf { it.isNotBlank() } ?: "unknown", confidence.clamped(), evidence.cleanEvidence())

private fun EvidenceScores.cleanScores(): EvidenceScores = copy(
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
    intensity = intensity.cleanNumber(0.5),
    speechiness = speechiness.cleanNumber(0.5),
    instrumentalness = instrumentalness.cleanNumber(0.5),
    liveness = liveness.cleanNumber(0.5),
    emotionalIntensity = emotionalIntensity.cleanNumber(0.5),
    lyricalFocus = lyricalFocus.cleanNumber(0.5),
    mainstreamAppeal = mainstreamAppeal.cleanNumber(0.5)
)

private fun EvidenceValueDouble.cleanNumber(default: Double): EvidenceValueDouble =
    EvidenceValueDouble(value.clamped(default), confidence.clamped(), evidence.cleanEvidence())

private fun List<EvidenceTag>.cleanTags(): List<EvidenceTag> =
    filter { it.tag.isNotBlank() }
        .map { EvidenceTag(safeFileStem(it.tag).take(ANALYSIS_TAG_MAX_CHARS), it.confidence.clamped(), it.evidence.cleanEvidence()) }
        .filter { it.tag.isNotBlank() }
        .distinctBy { it.tag }
        .take(ANALYSIS_TAG_LIMIT)

private fun List<String>.cleanEvidence(): List<String> {
    val allowed = setOf("metadata", "lyrics", "playlist_context", "model_inference")
    return filter { it in allowed }.distinct()
}

private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.text(name: String): String? = get(name)?.text()
private fun JsonObject.number(name: String): Double? = get(name)?.number()
private fun JsonObject.boolean(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.stringArray(name: String): List<String>? =
    (get(name) as? JsonArray)?.mapNotNull { it.text() }?.takeIf { it.isNotEmpty() }

private fun Double.clamped(default: Double = 0.0): Double =
    if (isFinite()) min(1.0, max(0.0, this)) else default
