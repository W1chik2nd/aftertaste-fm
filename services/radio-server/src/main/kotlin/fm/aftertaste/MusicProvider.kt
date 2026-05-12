package fm.aftertaste

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

interface MusicProvider {
    val name: String

    suspend fun search(query: String): List<Track>
    suspend fun getTrack(trackId: String): Track?
    suspend fun getStreamUrl(trackId: String): StreamUrl
    suspend fun getStreamUrls(trackIds: List<String>): List<StreamUrl> =
        trackIds.map { getStreamUrl(it) }
    suspend fun getLyrics(trackId: String): String?
    suspend fun getPlaylist(playlistId: String): Playlist
    suspend fun getRecommendations(context: RecommendationContext): List<Track>
    suspend fun healthCheck(): ProviderHealth = ProviderHealth(status = "unknown")
}

data class ProviderHealth(val status: String, val mode: String? = null)

class MockMusicProvider : MusicProvider {
    override val name: String = "mock"

    private val tracks = listOf(
        Track("mock", "mk-001", "Night Bus Past the River", "Low Lantern", "Small Hours", 221000, "https://images.unsplash.com/photo-1493246507139-91e8fad9978e?auto=format&fit=crop&w=900&q=80"),
        Track("mock", "mk-002", "Soft Debugging", "Mina Shore", "Quiet Machines", 198000, "https://images.unsplash.com/photo-1516280440614-37939bbacd81?auto=format&fit=crop&w=900&q=80"),
        Track("mock", "mk-003", "把灯关小一点", "林屿", "城市低语", 244000, "https://images.unsplash.com/photo-1483412033650-1015ddeb83d1?auto=format&fit=crop&w=900&q=80"),
        Track("mock", "mk-004", "Rain Check at 1:17", "North Exit", "After Work Weather", 205000, "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=900&q=80"),
        Track("mock", "mk-005", "Slow Cache Before Commute", "Ada Vale", "Warm Boot", 232000, "https://images.unsplash.com/photo-1495567720989-cebdbdd97913?auto=format&fit=crop&w=900&q=80"),
        Track("mock", "mk-006", "月台没有风", "白鸟计划", "晚班车", 256000, "https://images.unsplash.com/photo-1500534314209-a25ddb2bd429?auto=format&fit=crop&w=900&q=80"),
        Track("mock", "mk-007", "Window Seat, No Talking", "The Minor Rooms", "Hold Music for Feelings", 213000, "https://images.unsplash.com/photo-1519681393784-d120267933ba?auto=format&fit=crop&w=900&q=80"),
        Track("mock", "mk-008", "A Little Less Blue", "Juniper Tape", "Kind Edges", 227000, "https://images.unsplash.com/photo-1515405295579-ba7b45403062?auto=format&fit=crop&w=900&q=80")
    )

    override suspend fun search(query: String): List<Track> =
        tracks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
        }.ifEmpty { tracks.take(5) }

    override suspend fun getTrack(trackId: String): Track? = tracks.find { it.id == trackId }

    override suspend fun getStreamUrl(trackId: String): StreamUrl =
        StreamUrl("mock", trackId, url = null, quality = "unknown", reason = "unavailable")

    override suspend fun getStreamUrls(trackIds: List<String>): List<StreamUrl> =
        trackIds.map { getStreamUrl(it) }

    override suspend fun getLyrics(trackId: String): String? = null

    override suspend fun getPlaylist(playlistId: String): Playlist =
        Playlist(
            provider = "mock",
            id = playlistId,
            name = "Mock Late-Night Shelf",
            description = "A local fallback playlist for developing Aftertaste FM without platform credentials.",
            coverUrl = tracks.firstOrNull()?.coverUrl,
            tracks = tracks
        )

    override suspend fun getRecommendations(context: RecommendationContext): List<Track> {
        val quietBias = context.routing.energy == "low" ||
            context.mood?.contains("quiet", ignoreCase = true) == true ||
            context.mood?.contains("late", ignoreCase = true) == true
        return if (quietBias) tracks else tracks.shuffled().take(tracks.size)
    }

    override suspend fun healthCheck(): ProviderHealth = ProviderHealth(status = "ok", mode = "mock")
}

class NeteaseMusicProvider(
    private val baseUrl: String,
    private val fallback: MusicProvider? = MockMusicProvider(),
    private val client: HttpClient = HttpClients.shared
) : MusicProvider {
    override val name: String = "netease"
    private val logger = LoggerFactory.getLogger(NeteaseMusicProvider::class.java)
    private val parser: Json = HttpClients.sharedJson

    override suspend fun search(query: String): List<Track> =
        runCatching { client.get("$baseUrl/search?keywords=${query.encodeURLParameter()}").body<List<Track>>() }
            .getOrElse { error ->
                logger.warn("netease search fallback: {}", error.message)
                fallback?.search(query) ?: throw error
            }

    override suspend fun getTrack(trackId: String): Track? =
        runCatching { client.get("$baseUrl/song/detail?id=${trackId.encodeURLParameter()}").body<Track>() }
            .getOrNull() ?: fallback?.getTrack(trackId)

    override suspend fun getStreamUrl(trackId: String): StreamUrl =
        getStreamUrls(listOf(trackId)).firstOrNull()
            ?: StreamUrl("netease", trackId, url = null, reason = "adapter_unreachable")

    override suspend fun getStreamUrls(trackIds: List<String>): List<StreamUrl> {
        if (trackIds.isEmpty()) return emptyList()
        return runCatching {
            val ids = trackIds.joinToString(",").encodeURLParameter()
            val text = client.get("$baseUrl/song/url?id=$ids").body<String>()
            parser.decodeFromString(ListSerializer(StreamUrl.serializer()), text)
        }.getOrElse { error ->
            logger.warn("netease getStreamUrls fallback: {}", error.message)
            trackIds.map { trackId -> StreamUrl("netease", trackId, url = null, reason = "adapter_unreachable") }
        }
    }

    override suspend fun getLyrics(trackId: String): String? =
        runCatching { client.get("$baseUrl/lyric?id=${trackId.encodeURLParameter()}").body<Map<String, String?>>()["lyrics"] }
            .getOrNull()

    override suspend fun getPlaylist(playlistId: String): Playlist =
        runCatching { client.get("$baseUrl/playlist/detail?id=${playlistId.encodeURLParameter()}").body<Playlist>() }
            .getOrElse { fallback?.getPlaylist(playlistId) ?: throw it }

    override suspend fun getRecommendations(context: RecommendationContext): List<Track> =
        runCatching { client.get("$baseUrl/recommend/songs").body<List<Track>>() }
            .getOrElse { fallback?.getRecommendations(context) ?: throw it }
            .ifEmpty { fallback?.getRecommendations(context) ?: emptyList() }

    override suspend fun healthCheck(): ProviderHealth =
        runCatching {
            val body = client.get("$baseUrl/health").body<Map<String, String>>()
            ProviderHealth(status = body["status"] ?: "ok", mode = body["mode"])
        }.getOrElse { ProviderHealth(status = "offline") }
}
