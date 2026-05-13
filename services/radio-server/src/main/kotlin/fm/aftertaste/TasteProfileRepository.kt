package fm.aftertaste

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class TasteProfileRepository(
    private val tastePath: Path = Env.path("TASTE_DATA_DIR", "data/taste"),
    private val examplePath: Path = Env.path("TASTE_EXAMPLE_DIR", "data/taste.example")
) {
    private val logger = LoggerFactory.getLogger(TasteProfileRepository::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): TasteProfile {
        val root = when {
            Files.exists(tastePath.resolve("tracks.evidence.json")) -> tastePath
            Files.exists(tastePath.resolve("tracks.json")) -> tastePath
            Files.exists(examplePath.resolve("tracks.json")) -> examplePath
            else -> null
        }
        if (root == null) {
            return TasteProfile(
                profileText = "No taste profile found. Using provider recommendations.",
                rules = TasteRules(),
                tracks = emptyList(),
                source = "none"
            )
        }

        val profileText = readStringIfExists(root.resolve("profile.md"))
            ?: "Example taste profile loaded."
        val rules = readStringIfExists(root.resolve("rules.json"))
            ?.let { content -> decodeOrNull<TasteRules>(content, root.resolve("rules.json")) }
            ?: TasteRules()
        val tracks = loadTracks(root)

        return TasteProfile(
            profileText = profileText,
            rules = rules,
            tracks = tracks,
            source = root.toString()
        )
    }

    private fun loadTracks(root: Path): List<TaggedTrack> {
        readStringIfExists(root.resolve("tracks.evidence.json"))
            ?.let { content ->
                decodeOrNull<EvidencePlaylistAnalysis>(content, root.resolve("tracks.evidence.json"))
                    ?.tracks
                    ?.filterNot {
                        it.needsReview && it.evidence.lyrics.not() && it.evidence.manual.not() && it.evidence.model.not()
                    }
                    ?.map { it.toTaggedTrack() }
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        readStringIfExists(root.resolve("tracks.json"))
            ?.let { content ->
                decodeOrNull(content, root.resolve("tracks.json"), ListSerializer(TaggedTrack.serializer()))
            }
            ?.let { return it }

        return emptyList()
    }

    private inline fun <reified T> decodeOrNull(content: String, path: Path): T? =
        try {
            json.decodeFromString<T>(content)
        } catch (cause: SerializationException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        } catch (cause: IllegalArgumentException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        }

    private fun decodeOrNull(
        content: String,
        path: Path,
        serializer: kotlinx.serialization.KSerializer<List<TaggedTrack>>
    ): List<TaggedTrack>? =
        try {
            json.decodeFromString(serializer, content)
        } catch (cause: SerializationException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        } catch (cause: IllegalArgumentException) {
            logger.warn("Ignoring invalid taste file {}: {}", path, cause.message)
            null
        }
}

private fun readStringIfExists(path: Path): String? =
    try {
        if (Files.exists(path)) Files.readString(path) else null
    } catch (cause: IOException) {
        org.slf4j.LoggerFactory.getLogger("fm.aftertaste.TasteProfileRepository")
            .warn("Could not read taste file {}: {}", path, cause.message)
        null
    }
