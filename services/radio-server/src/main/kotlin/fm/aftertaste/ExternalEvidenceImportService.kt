package fm.aftertaste

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class ExternalEvidenceImportException(message: String) : RuntimeException(message)

class ExternalEvidenceImportService(
    private val evidence: EvidenceLibraryService
) {
    private val json = HttpClients.sharedJson

    suspend fun importJson(request: ImportEvidenceJsonRequest): ImportEvidenceJsonResponse {
        val parsed = parseTracks(request.content)
        val existing = evidence.list().map { it.identity() }.toMutableSet()
        val imported = mutableListOf<EvidenceTrackAnalysis>()
        for (track in parsed) {
            if (existing.add(track.identity())) {
                evidence.save(track)
                imported += track
            }
        }
        if (imported.isNotEmpty()) evidence.rebuildAggregate()
        return ImportEvidenceJsonResponse(
            importedTrackCount = imported.size,
            ignoredDuplicateCount = parsed.size - imported.size,
            totalTrackCount = parsed.size,
            sourceName = request.sourceName
        )
    }

    private fun parseTracks(content: String): List<EvidenceTrackAnalysis> {
        val element = try {
            json.parseToJsonElement(content)
        } catch (cause: SerializationException) {
            throw ExternalEvidenceImportException("Invalid JSON: ${cause.message}")
        }
        return when (element) {
            is JsonObject -> parseObject(element)
            is JsonArray -> json.decodeFromString(ListSerializer(EvidenceTrackAnalysis.serializer()), content)
            else -> throw ExternalEvidenceImportException("Expected a tracks.evidence.json object or an array of tracks.")
        }.also { tracks ->
            if (tracks.isEmpty()) throw ExternalEvidenceImportException("No tracks found in JSON.")
        }
    }

    private fun parseObject(element: JsonObject): List<EvidenceTrackAnalysis> {
        val tracks = element["tracks"]
            ?: throw ExternalEvidenceImportException("JSON object must include a tracks array.")
        return try {
            json.decodeFromJsonElement(ListSerializer(EvidenceTrackAnalysis.serializer()), tracks)
        } catch (cause: SerializationException) {
            throw ExternalEvidenceImportException("JSON tracks do not match EvidenceTrackAnalysis: ${cause.message}")
        }
    }
}
