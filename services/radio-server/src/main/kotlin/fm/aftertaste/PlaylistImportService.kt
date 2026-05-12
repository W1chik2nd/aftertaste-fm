package fm.aftertaste

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class PlaylistImportService(
    private val importsPath: Path = Env.path("TASTE_IMPORTS_DIR", "data/taste/imports"),
    private val draftsPath: Path = Env.path("TASTE_DRAFTS_DIR", "data/taste/drafts"),
    private val lyricsPath: Path = Env.path("TASTE_LYRICS_DIR", "data/taste/lyrics")
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

    private fun guessLanguage(title: String, artist: String): String {
        val text = title + artist
        return when {
            text.any { it in '぀'..'ゟ' || it in '゠'..'ヿ' } -> "ja"
            text.any { it in '가'..'힯' } -> "ko"
            text.any { isCjkUnified(it) } -> "zh-CN"
            text.all { it.code <= 127 } -> "en"
            else -> "unknown"
        }
    }

    private fun isCjkUnified(ch: Char): Boolean =
        ch in '㐀'..'䶿' || ch in '一'..'鿿' || ch in '豈'..'﫿'

    private fun safeSlug(value: String): String {
        val builder = StringBuilder(value.length)
        var lastDash = false
        value.lowercase().forEach { ch ->
            val keep = ch.isDigit() || (ch in 'a'..'z') ||
                ch in '぀'..'ゟ' || ch in '゠'..'ヿ' || // kana
                ch in '가'..'힯' ||                              // hangul syllables
                isCjkUnified(ch)
            if (keep) {
                builder.append(ch)
                lastDash = false
            } else if (!lastDash) {
                builder.append('-')
                lastDash = true
            }
        }
        return builder.toString().trim('-').take(96).ifBlank { "playlist" }
    }
}
