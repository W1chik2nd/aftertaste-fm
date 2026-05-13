package fm.aftertaste

private val whitespacePattern = Regex("\\s+")

data class TrackIdentity(val title: String, val artist: String)

fun trackIdentity(title: String, artist: String): TrackIdentity =
    TrackIdentity(normalizeTrackText(title), normalizeTrackText(artist))

fun Track.identity(): TrackIdentity = trackIdentity(title, artist)

fun TaggedTrack.identity(): TrackIdentity = trackIdentity(title, artist)

fun EvidenceTrackAnalysis.identity(): TrackIdentity = trackIdentity(title, artist)

private fun normalizeTrackText(value: String): String =
    value.trim().lowercase().replace(whitespacePattern, " ")
