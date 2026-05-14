package fm.aftertaste

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticFiles
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

private const val DEFAULT_PORT = 8080
private const val DEFAULT_BIND_HOST = "127.0.0.1"
private const val DEFAULT_WS_STREAM_INTERVAL_MS = 2000L

fun main() {
    val port = Env.value("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val host = Env.value("RADIO_BIND_HOST") ?: DEFAULT_BIND_HOST
    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

private class AppServices(
    val json: Json,
    val hostConfig: HostConfig,
    val provider: MusicProvider,
    val neteaseRealProvider: NeteaseMusicProvider,
    val tasteRepository: TasteProfileRepository,
    val evidenceLibrary: EvidenceLibraryService,
    val playlistImportService: PlaylistImportService,
    val userRecordImportService: NeteaseUserRecordImportService,
    val engine: RadioEngine,
    val agentChat: AgentChatService,
    val jobScope: CoroutineScope,
    val analysisJobs: AnalysisJobService,
    val wsStreamIntervalMs: Long
)

private fun buildServices(): AppServices {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    val hostConfig = HostConfig(
        hostLanguage = Env.value("HOST_LANGUAGE") ?: "en-US",
        hostStyle = Env.value("HOST_VOICE_STYLE")?.trim()?.takeIf { it.isNotBlank() } ?: "calm late-night radio",
        hostName = Env.value("HOST_NAME") ?: "Aftertaste",
        segmentSpeechMode = "between_segments"
    )
    val adapterBaseUrl = Env.value("NETEASE_ADAPTER_BASE_URL") ?: "http://localhost:8090"
    val provider = when ((Env.value("MUSIC_PROVIDER") ?: "mock").lowercase()) {
        "netease" -> NeteaseMusicProvider(adapterBaseUrl)
        else -> MockMusicProvider()
    }
    // Import + adapter health both need a real netease provider regardless of MUSIC_PROVIDER mode,
    // so they share a single instance. Falling back to mock for these paths would defeat the point.
    val neteaseRealProvider = NeteaseMusicProvider(baseUrl = adapterBaseUrl, fallback = null)
    val tasteRepository = TasteProfileRepository()
    val evidenceLibrary = EvidenceLibraryService()
    val playlistImportService = PlaylistImportService()
    val userRecordImportService = NeteaseUserRecordImportService(neteaseRealProvider, playlistImportService)
    val engine = RadioEngine(
        provider, neteaseRealProvider, hostConfig, StateStore(),
        tasteRepository = tasteRepository,
        playlistImportService = playlistImportService
    )
    val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val analysisJobs = AnalysisJobService(
        imports = playlistImportService,
        evidence = evidenceLibrary,
        analyzer = TrackAnalysisService.fromEnvironment(),
        scope = jobScope
    )
    val wsIntervalMs = Env.value("WS_STREAM_INTERVAL_MS")?.toLongOrNull() ?: DEFAULT_WS_STREAM_INTERVAL_MS
    return AppServices(
        json, hostConfig, provider, neteaseRealProvider, tasteRepository, evidenceLibrary,
        playlistImportService, userRecordImportService, engine,
        AgentChatService.fromEnvironment(), jobScope, analysisJobs, wsIntervalMs
    )
}

fun Application.module() {
    val services = buildServices()
    environment.monitor.subscribe(ApplicationStopping) {
        services.analysisJobs.shutdown()
        services.jobScope.cancel()
    }
    installPlugins(services.json)
    routing {
        staticFiles("/media/tts", Env.path("TTS_CACHE_DIR", "cache/tts").toFile())
        route("/api") { registerApiRoutes(services) }
        webSocket("/ws/stream") { streamPlayback(services) }
    }
}

private fun Application.installPlugins(json: Json) {
    install(ContentNegotiation) { json(json) }
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
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }
}

private fun Route.registerApiRoutes(services: AppServices) {
    val engine = services.engine
    get("/health") {
        call.respond(HealthResponse("ok", services.provider.name, services.hostConfig, stationStyleFor(java.time.OffsetDateTime.now())))
    }
    get("/health/adapter") {
        val health = services.neteaseRealProvider.healthCheck()
        call.respond(AdapterHealthResponse(status = health.status, mode = health.mode))
    }
    get("/now") { call.respond(engine.now()) }
    get("/settings") { call.respond(engine.settings()) }
    post("/settings/location") {
        val request = call.receive<LocationRequest>()
        call.respond(engine.setWeatherLocation(request.location))
    }
    post("/settings/host-language") {
        val request = call.receive<HostLanguageRequest>()
        call.respond(engine.setHostLanguage(request.hostLanguage))
    }
    post("/weather/refresh") { call.respond(engine.refreshWeather()) }
    post("/play") { call.respond(engine.play()) }
    post("/pause") { call.respond(engine.pause()) }
    post("/next") { call.respond(engine.next()) }
    post("/previous") { call.respond(engine.previous()) }
    post("/playback/clear") { call.respond(engine.clearPlayback()) }
    post("/plan/today") { call.respond(engine.planToday()) }
    post("/chat") {
        val request = call.receive<ChatRequest>()
        call.respond(engine.planToday(request.message, request.routingIntent))
    }
    post("/agent/chat") {
        val request = call.receive<ChatRequest>()
        engine.rememberMessage("user", request.message)
        val response = services.agentChat.reply(request.message, engine.now(), services.hostConfig)
        response.command?.let { engine.handleCommand(it) }
        engine.rememberMessage("agent", response.message)
        call.respond(response)
    }
    get("/playlist/{id}") { call.respond(engine.playlist(call.parameters["id"].orEmpty())) }
    get("/lyrics/{id}") { call.respond(engine.lyrics(call.parameters["id"].orEmpty())) }
    registerImportRoutes(engine, services.playlistImportService, services.evidenceLibrary, services.analysisJobs, services.userRecordImportService)
    registerTasteRoutes(services.tasteRepository, services.evidenceLibrary)
}

private suspend fun DefaultWebSocketServerSession.streamPlayback(services: AppServices) {
    var lastSnapshot: String? = null
    while (isActive) {
        val snapshot = services.json.encodeToString(services.engine.now())
        if (snapshot != lastSnapshot) {
            send(Frame.Text(snapshot))
            lastSnapshot = snapshot
        }
        delay(services.wsStreamIntervalMs)
    }
}

@Serializable
data class AdapterHealthResponse(
    val status: String,
    val mode: String? = null
)

