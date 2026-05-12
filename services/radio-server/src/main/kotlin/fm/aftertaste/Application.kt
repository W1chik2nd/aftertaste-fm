package fm.aftertaste

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticFiles
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

fun main() {
    val port = Env.value("PORT")?.toIntOrNull() ?: 8080
    val host = Env.value("RADIO_BIND_HOST") ?: "127.0.0.1"
    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    val hostConfig = HostConfig(
        hostLanguage = Env.value("HOST_LANGUAGE") ?: "en-US",
        hostStyle = normalizeHostStyle(Env.value("HOST_VOICE_STYLE")) ?: "calm late-night radio",
        hostName = Env.value("HOST_NAME") ?: "Aftertaste",
        segmentSpeechMode = "between_segments"
    )
    val adapterBaseUrl = Env.value("NETEASE_ADAPTER_BASE_URL") ?: "http://localhost:8090"
    val provider = when ((Env.value("MUSIC_PROVIDER") ?: "mock").lowercase()) {
        "netease" -> NeteaseMusicProvider(adapterBaseUrl)
        else -> MockMusicProvider()
    }
    val neteaseImportProvider = NeteaseMusicProvider(baseUrl = adapterBaseUrl, fallback = null)
    val adapterHealthProvider = NeteaseMusicProvider(baseUrl = adapterBaseUrl, fallback = null)
    val engine = RadioEngine(provider, neteaseImportProvider, hostConfig, StateStore())
    val agentChat = AgentChatService.fromEnvironment()

    install(ContentNegotiation) {
        json(json)
    }
    install(WebSockets)
    install(CORS) {
        // Dev-only: allow localhost origins on any port. Production deployments should restrict this.
        allowHost("localhost", schemes = listOf("http", "https"))
        allowHost("127.0.0.1", schemes = listOf("http", "https"))
        Env.value("EXTRA_CORS_HOSTS")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.forEach { allowHost(it, schemes = listOf("http", "https")) }
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }

    routing {
        staticFiles("/media/tts", Env.path("TTS_CACHE_DIR", "cache/tts").toFile())

        route("/api") {
            get("/health") {
                call.respond(HealthResponse("ok", provider.name, hostConfig))
            }

            get("/health/adapter") {
                val health = adapterHealthProvider.healthCheck()
                call.respond(AdapterHealthResponse(status = health.status, mode = health.mode))
            }

            get("/now") {
                call.respond(engine.now())
            }

            get("/settings") {
                call.respond(engine.settings())
            }

            post("/settings/location") {
                val request = call.receive<LocationRequest>()
                call.respond(engine.setWeatherLocation(request.location))
            }

            post("/weather/refresh") {
                call.respond(engine.refreshWeather())
            }

            post("/play") {
                call.respond(engine.play())
            }

            post("/pause") {
                call.respond(engine.pause())
            }

            post("/next") {
                call.respond(engine.next())
            }

            post("/previous") {
                call.respond(engine.previous())
            }

            post("/playback/clear") {
                call.respond(engine.clearPlayback())
            }

            post("/plan/today") {
                call.respond(engine.planToday())
            }

            post("/chat") {
                val request = call.receive<ChatRequest>()
                call.respond(engine.planToday(request.message))
            }

            post("/agent/chat") {
                val request = call.receive<ChatRequest>()
                engine.rememberMessage("user", request.message)
                val response = agentChat.reply(request.message, engine.now(), hostConfig)
                response.command?.let { engine.handleCommand(it) }
                engine.rememberMessage("agent", response.message)
                call.respond(response)
            }

            get("/playlist/{id}") {
                val id = call.parameters["id"].orEmpty()
                call.respond(engine.playlist(id))
            }

            get("/lyrics/{id}") {
                val id = call.parameters["id"].orEmpty()
                call.respond(engine.lyrics(id))
            }

            post("/import/playlist") {
                val request = call.receive<ImportPlaylistRequest>()
                call.respond(engine.importPlaylist(request.source))
            }
        }

        val wsLogger = org.slf4j.LoggerFactory.getLogger("fm.aftertaste.WsStream")
        webSocket("/ws/stream") {
            val intervalMs = Env.value("WS_STREAM_INTERVAL_MS")?.toLongOrNull() ?: 2000L
            try {
                var lastSnapshot: String? = null
                while (isActive) {
                    val snapshot = json.encodeToString(engine.now())
                    if (snapshot != lastSnapshot) {
                        send(Frame.Text(snapshot))
                        lastSnapshot = snapshot
                    }
                    delay(intervalMs)
                }
            } catch (cause: Throwable) {
                wsLogger.debug("ws/stream closed: {}", cause.message)
            }
        }
    }
}

@Serializable
data class AdapterHealthResponse(
    val status: String,
    val mode: String? = null
)

/**
 * Accept either the user-facing form ("calm late-night radio") or the legacy kebab-case form
 * ("calm-late-night") that earlier versions documented in `.env.example`. We treat a single-token
 * dashed value as a stylistic abbreviation and expand it back to spaces; multi-token values are
 * passed through so styles like "low-key warm-fuzzy" aren't mangled.
 */
private fun normalizeHostStyle(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (' ' !in trimmed && '-' in trimmed) trimmed.replace('-', ' ') else trimmed
}
