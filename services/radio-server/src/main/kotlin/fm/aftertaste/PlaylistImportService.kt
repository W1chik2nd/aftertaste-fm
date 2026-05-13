package fm.aftertaste

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.name

private const val RAW_IMPORT_SUFFIX = ".raw.json"
private const val DRAFT_IMPORT_SUFFIX = ".tagged-draft.json"
private const val LYRICS_IMPORT_SUFFIX = ".lyrics.json"
private const val INITIAL_SCORE = 0.5
private const val INITIAL_SKIP_RISK = 0.2
private const val LANG_MIN_SCRIPT_CHARS = 2

class PlaylistImportService(
    private val importsPath: Path = Env.path("TASTE_IMPORTS_DIR", "data/taste/imports"),
    private val draftsPath: Path = Env.path("TASTE_DRAFTS_DIR", "data/taste/drafts"),
    private val lyricsPath: Path = Env.path("TASTE_LYRICS_DIR", "data/taste/lyrics")
) {
    private val logger = LoggerFactory.getLogger(PlaylistImportService::class.java)
    private val json = HttpClients.sharedJson

    /**
     * Cached set of `(title, artist)` identities seen across every previously imported playlist.
     * Saves a full disk re-scan on every import. Mutated only by [save].
     */
    private var identitiesCache: MutableSet<TrackIdentity>? = null
    private val identitiesMutex = Mutex()

    suspend fun save(
        source: String,
        playlist: Playlist,
        lyricsByTrackId: Map<String, String?>,
        includeExistingTracks: Boolean = false
    ): ImportPlaylistResponse {
        val importedAt = nowIso()
        val slug = slugFor(playlist)
        val rawPath = importsPath.resolve("$slug$RAW_IMPORT_SUFFIX").toAbsolutePath().normalize()
        val draftPath = draftsPath.resolve("$slug$DRAFT_IMPORT_SUFFIX").toAbsolutePath().normalize()
        val lyricPath = lyricsPath.resolve("$slug$LYRICS_IMPORT_SUFFIX").toAbsolutePath().normalize()
        val existingRaw = readRaw(rawPath)
        val existingDraft = readDraft(draftPath)
        val existingLyrics = readLyrics(lyricPath)
        val identities = ensureIdentities()
        val uniqueTracks = identitiesMutex.withLock {
            playlist.tracks.filter { track -> identities.add(track.identity()) }
        }
        val importTracks = if (includeExistingTracks) playlist.tracks.distinctBy { it.identity() } else uniqueTracks
        val ignoredDuplicateCount = playlist.tracks.size - uniqueTracks.size
        if (!includeExistingTracks && uniqueTracks.isEmpty() && existingRaw == null) {
            return duplicateOnlyResponse(slug, playlist, importedAt, ignoredDuplicateCount, rawPath, draftPath, lyricPath)
        }
        val combinedTracks = (existingRaw?.playlist?.tracks.orEmpty() + importTracks)
            .distinctBy { it.identity() }
        val uniquePlaylist = playlist.copy(tracks = combinedTracks)
        val mergedLyricsByTrackId = existingLyrics?.lyricsByTrackId.orEmpty() +
            importTracks.associate { track -> track.id to lyricsByTrackId[track.id] }
        val draftTracks = draftTracks(existingDraft, importTracks)

        val raw = ImportedPlaylistFile(importedAt = importedAt, source = source, playlist = uniquePlaylist)
        val draft = TaggedPlaylistDraft(
            importedAt = importedAt,
            source = source,
            playlistId = uniquePlaylist.id,
            playlistName = uniquePlaylist.name,
            tracks = existingDraft?.tracks.orEmpty() + draftTracks.map { it.toTaggedDraft() }
        )
        val lyrics = ImportedLyricsFile(
            importedAt = importedAt,
            source = source,
            playlistId = uniquePlaylist.id,
            playlistName = uniquePlaylist.name,
            lyricsByTrackId = mergedLyricsByTrackId
        )

        writeImportFiles(rawPath, draftPath, lyricPath, raw, draft, lyrics)

        return importResponse(slug, uniquePlaylist, importedAt, importTracks, ignoredDuplicateCount, mergedLyricsByTrackId, rawPath, draftPath, lyricPath)
    }

    private fun draftTracks(existingDraft: TaggedPlaylistDraft?, tracks: List<Track>): List<Track> =
        tracks.filterNot { it.identity() in existingDraft?.tracks.orEmpty().map { track -> track.identity() }.toSet() }

    private fun duplicateOnlyResponse(slug: String, playlist: Playlist, importedAt: String, ignoredDuplicateCount: Int, rawPath: Path, draftPath: Path, lyricPath: Path): ImportPlaylistResponse =
        ImportPlaylistResponse(slug, playlist.id, playlist.name, importedAt, 0, ignoredDuplicateCount, 0, 0, rawPath.toString(), draftPath.toString(), lyricPath.toString(), "All tracks already exist in the import library.")

    private suspend fun writeImportFiles(rawPath: Path, draftPath: Path, lyricPath: Path, raw: ImportedPlaylistFile, draft: TaggedPlaylistDraft, lyrics: ImportedLyricsFile) {
        AtomicFiles.writeString(rawPath, json.encodeToString(raw) + "\n")
        AtomicFiles.writeString(draftPath, json.encodeToString(draft) + "\n")
        AtomicFiles.writeString(lyricPath, json.encodeToString(lyrics) + "\n")
    }

    private fun importResponse(slug: String, playlist: Playlist, importedAt: String, tracks: List<Track>, ignoredDuplicateCount: Int, lyricsByTrackId: Map<String, String?>, rawPath: Path, draftPath: Path, lyricPath: Path): ImportPlaylistResponse =
        ImportPlaylistResponse(
            slug,
            playlist.id,
            playlist.name,
            importedAt,
            tracks.size,
            ignoredDuplicateCount,
            tracks.count { lyricsByTrackId[it.id] != null },
            tracks.count { lyricsByTrackId[it.id] == null },
            rawPath.toString(),
            draftPath.toString(),
            lyricPath.toString(),
            "Analyze this import to write per-track evidence files."
        )

    suspend fun list(evidence: EvidenceLibraryService): List<ImportRecord> {
        val analyzedKeys = evidence.existingKeys()
        return withContext(Dispatchers.IO) {
            rawImportFiles().mapNotNull { path ->
                readRaw(path)?.toRecord(slugFromRawPath(path), analyzedKeys)
            }.sortedByDescending { it.importedAt }
        }
    }

    suspend fun detail(slug: String, evidence: EvidenceLibraryService): ImportDetail? {
        val raw = withContext(Dispatchers.IO) {
            readRaw(importsPath.resolve("$slug$RAW_IMPORT_SUFFIX"))
        } ?: return null
        val analyzedKeys = evidence.existingKeys()
        val analyzedTracks = raw.playlist.tracks.filter { "${it.provider}:${it.id}" in analyzedKeys }
        val analyzedAt = analyzedTracks
            .mapNotNull { evidence.get(it.provider, it.id)?.lastAnalyzedAt }
            .maxOrNull()
        val record = raw.toRecord(slug, analyzedKeys)
        return ImportDetail(
            slug = record.slug,
            playlistId = record.playlistId,
            name = record.name,
            trackCount = record.trackCount,
            importedAt = record.importedAt,
            analyzedAt = analyzedAt ?: record.analyzedAt,
            status = record.status,
            analyzedTrackCount = record.analyzedTrackCount,
            pendingAnalysisCount = record.pendingAnalysisCount,
            tracks = raw.playlist.tracks.map { it.toSummary() }
        )
    }

    suspend fun draft(slug: String): TaggedPlaylistDraft? = withContext(Dispatchers.IO) {
        readDraft(draftsPath.resolve("$slug$DRAFT_IMPORT_SUFFIX"))
    }

    suspend fun lyrics(slug: String): ImportedLyricsFile? = withContext(Dispatchers.IO) {
        readLyrics(lyricsPath.resolve("$slug$LYRICS_IMPORT_SUFFIX"))
    }

    suspend fun delete(slug: String, evidence: EvidenceLibraryService): DeleteImportResponse {
        val rawPath = importsPath.resolve("$slug$RAW_IMPORT_SUFFIX")
        val draftPath = draftsPath.resolve("$slug$DRAFT_IMPORT_SUFFIX")
        val lyricPath = lyricsPath.resolve("$slug$LYRICS_IMPORT_SUFFIX")
        val raw = withContext(Dispatchers.IO) { readRaw(rawPath) }
        val deletedFiles = withContext(Dispatchers.IO) {
            listOf(rawPath, draftPath, lyricPath).count { Files.deleteIfExists(it) }
        }
        identitiesMutex.withLock { identitiesCache = null }
        val deletedEvidence = raw?.playlist?.tracks.orEmpty()
            .filterNot { isReferencedByOtherImport(it, slug) }
            .count { evidence.delete(it.provider, it.id) }
        return DeleteImportResponse(slug, deletedFiles > 0, deletedEvidence)
    }

    private fun rawImportFiles(): List<Path> {
        if (!Files.exists(importsPath)) return emptyList()
        return Files.list(importsPath).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.name.endsWith(RAW_IMPORT_SUFFIX) }
                .toList()
        }
    }

    private suspend fun ensureIdentities(): MutableSet<TrackIdentity> {
        identitiesCache?.let { return it }
        return identitiesMutex.withLock {
            identitiesCache ?: loadIdentitiesFromDisk().also { identitiesCache = it }
        }
    }

    private suspend fun loadIdentitiesFromDisk(): MutableSet<TrackIdentity> =
        withContext(Dispatchers.IO) {
            rawImportFiles()
                .mapNotNull { readRaw(it) }
                .flatMap { imported -> imported.playlist.tracks.map { it.identity() } }
                .toMutableSet()
        }

    private fun isReferencedByOtherImport(track: Track, deletedSlug: String): Boolean =
        rawImportFiles()
            .filterNot { slugFromRawPath(it) == deletedSlug }
            .mapNotNull { readRaw(it) }
            .any { imported -> imported.playlist.tracks.any { it.provider == track.provider && it.id == track.id } }

    private fun readRaw(path: Path): ImportedPlaylistFile? =
        readFile(path) { content -> json.decodeFromString<ImportedPlaylistFile>(content) }

    private fun readDraft(path: Path): TaggedPlaylistDraft? =
        readFile(path) { content -> json.decodeFromString<TaggedPlaylistDraft>(content) }

    private fun readLyrics(path: Path): ImportedLyricsFile? =
        readFile(path) { content -> json.decodeFromString<ImportedLyricsFile>(content) }

    private fun <T> readFile(path: Path, decode: (String) -> T): T? {
        if (!Files.exists(path)) return null
        return try {
            decode(Files.readString(path))
        } catch (cause: SerializationException) {
            logger.warn("Skipping invalid import file {}: {}", path, cause.message)
            null
        } catch (cause: IOException) {
            logger.warn("Skipping unreadable import file {}: {}", path, cause.message)
            null
        }
    }

    private fun ImportedPlaylistFile.toRecord(
        slug: String,
        analyzedKeys: Set<String>
    ): ImportRecord {
        val analyzedCount = playlist.tracks.count { "${it.provider}:${it.id}" in analyzedKeys }
        val status = when {
            analyzedCount == 0 -> "imported"
            analyzedCount == playlist.tracks.size -> "analyzed"
            else -> "partial"
        }
        return ImportRecord(
            slug = slug,
            playlistId = playlist.id,
            name = playlist.name,
            trackCount = playlist.tracks.size,
            importedAt = importedAt,
            analyzedAt = null,
            status = status,
            analyzedTrackCount = analyzedCount,
            pendingAnalysisCount = playlist.tracks.size - analyzedCount
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
            playCount = playCount,
            tags = emptyList(),
            language = guessLanguage(title, artist),
            energy = INITIAL_SCORE,
            valence = INITIAL_SCORE,
            nightScore = INITIAL_SCORE,
            codingScore = INITIAL_SCORE,
            skipRisk = INITIAL_SKIP_RISK
        )

    private fun Track.toSummary(): TrackSummary =
        TrackSummary(provider, id, title, artist, album, durationMs, coverUrl, playCount)

    private fun guessLanguage(title: String, artist: String): String {
        val text = title + artist
        if (text.isEmpty()) return "unknown"
        val nonAscii = text.count { it.code > 127 }
        val kana = text.count { it in '぀'..'ゟ' || it in '゠'..'ヿ' }
        val hangul = text.count { it in '가'..'힯' }
        val cjk = text.count { isCjkUnified(it) }
        return when {
            // Require a non-trivial share of script-specific characters so a single stylistic kana
            // (・, 〜) inside an otherwise Chinese-named track doesn't flip the language.
            hangul >= LANG_MIN_SCRIPT_CHARS && hangul * 2 >= nonAscii -> "ko"
            kana >= LANG_MIN_SCRIPT_CHARS && kana * 2 >= nonAscii -> "ja"
            cjk >= LANG_MIN_SCRIPT_CHARS -> "zh-CN"
            text.all { it.code <= 127 } -> "en"
            else -> "unknown"
        }
    }

    private fun slugFor(playlist: Playlist): String =
        safeFileStem("${playlist.provider}-${playlist.id}-${playlist.name}")

    private fun slugFromRawPath(path: Path): String =
        path.name.removeSuffix(RAW_IMPORT_SUFFIX)

    private fun nowIso(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
