package fm.aftertaste

class NeteaseUserRecordImportException(message: String) : RuntimeException(message)

class NeteaseUserRecordImportService(
    private val provider: NeteaseMusicProvider,
    private val imports: PlaylistImportService
) {
    suspend fun importAllTime(uid: String): ImportPlaylistResponse {
        val cleanUid = uid.trim().takeIf { it.isNotBlank() }
            ?: throw NeteaseUserRecordImportException("Netease uid is required.")
        val playlist = try {
            provider.getUserRecord(cleanUid)
        } catch (cause: NeteaseProviderException) {
            throw NeteaseUserRecordImportException(cause.message ?: "Netease listening ranking import failed.")
        }
        val lyricsByTrackId = fetchLyrics(playlist)
        return imports.save(
            source = "netease:user-record:$cleanUid:type=all",
            playlist = playlist,
            lyricsByTrackId = lyricsByTrackId
        )
    }

    private suspend fun fetchLyrics(playlist: Playlist): Map<String, String?> {
        val lyrics = linkedMapOf<String, String?>()
        for (track in playlist.tracks) {
            lyrics[track.id] = provider.getLyrics(track.id)?.trim()?.takeIf { it.isNotBlank() }
        }
        return lyrics
    }
}
