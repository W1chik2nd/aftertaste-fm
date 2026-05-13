package fm.aftertaste

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.registerImportRoutes(
    engine: RadioEngine,
    imports: PlaylistImportService,
    evidence: EvidenceLibraryService,
    jobs: AnalysisJobService
) {
    val externalEvidence = ExternalEvidenceImportService(evidence)

    post("/import/playlist") {
        val request = call.receive<ImportPlaylistRequest>()
        call.respond(engine.importPlaylist(request.source))
    }

    post("/import/evidence-json") {
        val request = call.receive<ImportEvidenceJsonRequest>()
        try {
            call.respond(externalEvidence.importJson(request))
        } catch (cause: ExternalEvidenceImportException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Invalid evidence JSON."))
        }
    }

    get("/imports") {
        call.respond(imports.list(evidence))
    }

    get("/imports/{slug}") {
        val slug = call.parameters["slug"].orEmpty()
        val detail = imports.detail(slug, evidence)
        if (detail == null) call.respondNotFound("Import not found: $slug") else call.respond(detail)
    }

    delete("/imports/{slug}") {
        val slug = call.parameters["slug"].orEmpty()
        val response = imports.delete(slug, evidence)
        if (response.deleted) call.respond(response) else call.respondNotFound("Import not found: $slug")
    }

    post("/imports/{slug}/analyze") {
        val slug = call.parameters["slug"].orEmpty()
        val request = call.receive<AnalyzeImportRequest>()
        try {
            call.respond(jobs.start(slug, request))
        } catch (cause: AnalysisUnavailableException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Analysis is unavailable."))
        } catch (cause: ImportNotFoundException) {
            call.respondNotFound(cause.message ?: "Import not found.")
        }
    }

    get("/jobs/{jobId}") {
        val jobId = call.parameters["jobId"].orEmpty()
        try {
            call.respond(jobs.get(jobId))
        } catch (cause: JobNotFoundException) {
            call.respondNotFound(cause.message ?: "Job not found.")
        }
    }

    delete("/jobs/{jobId}") {
        val jobId = call.parameters["jobId"].orEmpty()
        try {
            call.respond(jobs.cancel(jobId))
        } catch (cause: JobNotFoundException) {
            call.respondNotFound(cause.message ?: "Job not found.")
        }
    }
}

fun Route.registerTasteRoutes(
    tasteRepository: TasteProfileRepository,
    evidence: EvidenceLibraryService
) {
    get("/taste/tracks") {
        val query = call.request.queryParameters
        call.respond(
            evidence.query(
                evidence.queryFromParameters(
                    language = query["language"],
                    minConfidence = query["minConfidence"],
                    tag = query["tag"],
                    sort = query["sort"],
                    limit = query["limit"],
                    offset = query["offset"]
                )
            )
        )
    }

    get("/taste/tracks/{provider}/{id}") {
        val provider = call.parameters["provider"].orEmpty()
        val id = call.parameters["id"].orEmpty()
        val track = evidence.get(provider, id)
        if (track == null) call.respondNotFound("Track evidence not found: $provider/$id") else call.respond(track)
    }

    delete("/taste/tracks/{provider}/{id}") {
        val provider = call.parameters["provider"].orEmpty()
        val id = call.parameters["id"].orEmpty()
        val deleted = evidence.delete(provider, id)
        if (deleted) call.respond(DeleteTrackEvidenceResponse(provider, id, true)) else call.respondNotFound("Track evidence not found: $provider/$id")
    }

    get("/taste/tags") {
        call.respond(TasteTagsResponse(evidence.distinctTagNames()))
    }

    get("/taste/profile") {
        val profile = tasteRepository.load()
        call.respond(TasteProfileResponse(profile.profileText, profile.rules, profile.source))
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondNotFound(message: String) {
    respond(HttpStatusCode.NotFound, ErrorResponse(message))
}
