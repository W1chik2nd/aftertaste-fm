package fm.aftertaste

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

private val mediaProxyLogger = LoggerFactory.getLogger("fm.aftertaste.MediaProxy")

/**
 * Idle socket timeout for an upstream audio fetch. Generous enough to outlast a
 * full browser buffer (which can idle the socket for minutes on a long track),
 * but finite so a client that drops without closing still frees the connection.
 */
private const val STREAM_SOCKET_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * Same-origin audio proxy: `GET /media/stream?url=<encoded upstream url>`.
 *
 * External provider stream URLs are cross-origin to the web app, which taints
 * the `<audio>` element and blocks the Web Audio analyser. Routing playback
 * through this endpoint keeps the stream same-origin, so the analyser can read
 * real samples. The route only fetches stream URLs already present in the
 * current playback queue, so it is not an open proxy and does not know about
 * provider-specific hostnames.
 */
fun Route.registerMediaProxyRoutes(engine: RadioEngine) {
    get("/media/stream") {
        val rawUrl = call.request.queryParameters["url"]
        if (rawUrl.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing url parameter.")
            return@get
        }
        val target = try {
            URI(rawUrl)
        } catch (cause: URISyntaxException) {
            call.respond(HttpStatusCode.BadRequest, "Invalid url parameter.")
            return@get
        }
        val host = target.host
        val scheme = target.scheme?.lowercase()
        if (host == null || (scheme != "http" && scheme != "https")) {
            call.respond(HttpStatusCode.BadRequest, "Invalid url parameter.")
            return@get
        }
        if (!engine.now().hasStreamUrl(rawUrl)) {
            mediaProxyLogger.warn("Blocked media proxy URL outside playback queue: {}", host)
            call.respond(HttpStatusCode.Forbidden, "Stream URL not allowed.")
            return@get
        }

        var responseStarted = false
        try {
            HttpClients.shared.prepareGet(rawUrl) {
                // The shared client caps a request at 2 minutes and a socket at
                // 2 minutes idle. An audio stream legitimately outlives both: it
                // stays open for the whole track and goes idle whenever the
                // browser's buffer is full. The request timeout is lifted so a
                // long track is never cut mid-song; the socket timeout is only
                // widened, not removed, so a client that drops without closing
                // (network loss, killed tab) still releases the upstream
                // connection instead of leaking it forever.
                timeout {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MS
                }
                call.request.headers[HttpHeaders.Range]?.let { header(HttpHeaders.Range, it) }
            }.execute { upstream ->
                val contentType = upstream.contentType() ?: ContentType.Application.OctetStream
                upstream.headers[HttpHeaders.ContentRange]?.let { call.response.header(HttpHeaders.ContentRange, it) }
                upstream.headers[HttpHeaders.AcceptRanges]?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
                val contentLength = upstream.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                responseStarted = true
                call.respondBytesWriter(
                    contentType = contentType,
                    status = upstream.status,
                    contentLength = contentLength
                ) {
                    upstream.bodyAsChannel().copyAndClose(this)
                }
            }
        } catch (cause: HttpRequestTimeoutException) {
            call.respondProxyFailure(host, responseStarted, cause.message)
        } catch (cause: IOException) {
            call.respondProxyFailure(host, responseStarted, cause.message)
        } catch (cause: CancellationException) {
            throw cause
        }
    }
}

private fun PlaybackState.hasStreamUrl(url: String): Boolean =
    queue.any { it.track?.streamUrl == url }

private suspend fun io.ktor.server.application.ApplicationCall.respondProxyFailure(
    host: String,
    responseStarted: Boolean,
    message: String?
) {
    mediaProxyLogger.warn("Media proxy failed for {}: {}", host, message)
    if (!responseStarted) respond(HttpStatusCode.BadGateway, "Upstream media fetch failed.")
}
