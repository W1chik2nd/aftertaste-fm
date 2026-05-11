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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun main() {
    val port = Env.value("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
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
        hostStyle = (Env.value("HOST_VOICE_STYLE") ?: "calm-late-night").replace("-", " "),
        hostName = Env.value("HOST_NAME") ?: "Aftertaste",
        segmentSpeechMode = "between_segments"
    )
    val provider = when ((Env.value("MUSIC_PROVIDER") ?: "mock").lowercase()) {
        "netease" -> NeteaseMusicProvider(Env.value("NETEASE_ADAPTER_BASE_URL") ?: "http://localhost:8090")
        else -> MockMusicProvider()
    }
    val neteaseImportProvider = NeteaseMusicProvider(
        baseUrl = Env.value("NETEASE_ADAPTER_BASE_URL") ?: "http://localhost:8090",
        fallback = null
    )
    val engine = RadioEngine(provider, neteaseImportProvider, hostConfig, StateStore())
    val agentChat = AgentChatService.fromEnvironment()

    install(ContentNegotiation) {
        json(json)
    }
    install(WebSockets)
    install(CORS) {
        anyHost()
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

            post("/plan/today") {
                call.respond(engine.planToday())
            }

            post("/chat") {
                val request = call.receive<ChatRequest>()
                call.respond(engine.planToday(request.message))
            }

            post("/agent/chat") {
                val request = call.receive<ChatRequest>()
                call.respond(agentChat.reply(request.message, engine.now(), hostConfig))
            }

            get("/playlist/{id}") {
                val id = call.parameters["id"].orEmpty()
                call.respond(engine.playlist(id))
            }

            post("/import/playlist") {
                val request = call.receive<ImportPlaylistRequest>()
                call.respond(engine.importPlaylist(request.source))
            }
        }

        webSocket("/ws/stream") {
            while (true) {
                send(Frame.Text(json.encodeToString(engine.now())))
                delay(2000)
            }
        }
    }
}
