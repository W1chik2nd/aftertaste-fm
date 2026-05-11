package fm.aftertaste

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class PlaylistImportService(
    private val importsPath: Path = Path.of("../../data/taste/imports"),
    private val draftsPath: Path = Path.of("../../data/taste/drafts"),
    private val lyricsPath: Path = Path.of("../../data/taste/lyrics")
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(source: String, playlist: Playlist): ImportPlaylistResponse {
        val importedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        Files.createDirectories(importsPath)
        Files.createDirectories(draftsPath)
        Files.createDirectories(lyricsPath)

        val slug = safeSlug("${playlist.provider}-${playlist.id}-${playlist.name}")
        val rawPath = importsPath.resolve("$slug.raw.json").toAbsolutePath().normalize()
        val draftPath = draftsPath.resolve("$slug.tagged-draft.json").toAbsolutePath().normalize()
        val lyricPath = lyricsPath.resolve("$slug.lyrics.json").toAbsolutePath().normalize()

        Files.writeString(
            rawPath,
            json.encodeToString(
                ImportedPlaylistFile(
                    importedAt = importedAt,
                    source = source,
                    playlist = playlist
                )
            )
        )

        Files.writeString(
            draftPath,
            json.encodeToString(
                TaggedPlaylistDraft(
                    importedAt = importedAt,
                    source = source,
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    tracks = playlist.tracks.map { it.toTaggedDraft() }
                )
            )
        )

        Files.writeString(
            lyricPath,
            json.encodeToString(
                ImportedLyricsFile(
                    importedAt = importedAt,
                    source = source,
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    lyricsByTrackId = playlist.tracks.associate { it.id to null }
                )
            )
        )

        return ImportPlaylistResponse(
            playlist = playlist,
            importedAt = importedAt,
            rawPath = rawPath.toString(),
            taggedDraftPath = draftPath.toString(),
            lyricsPath = lyricPath.toString(),
            trackCount = playlist.tracks.size,
            nextStep = "Fetch lyrics into the lyrics file, then generate evidence-based analysis into data/taste/tracks.evidence.json. No runtime LLM analysis was triggered by this import."
        )
    }

    private fun Track.toTaggedDraft(): TaggedTrack =
        TaggedTrack(
            provider = provider,
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = coverUrl,
            tags = emptyList(),
            language = guessLanguage(title, artist),
            energy = 0.5,
            valence = 0.5,
            nightScore = 0.5,
            codingScore = 0.5,
            skipRisk = 0.2,
            notes = "TODO: manually tag this imported track."
        )

    private fun guessLanguage(title: String, artist: String): String =
        if ((title + artist).any { it.code > 127 }) "zh-CN" else "unknown"

    private fun safeSlug(value: String): String =
        value.lowercase()
            .replace(Regex("""[^a-z0-9\u4e00-\u9fa5]+"""), "-")
            .trim('-')
            .take(96)
            .ifBlank { "playlist" }
}
