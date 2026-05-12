package fm.aftertaste

/**
 * Small stop-gap heuristic that turns a user prompt into a typed [RoutingIntent].
 * This is intentionally the ONLY place in the codebase that does substring matching
 * on user prompts; everything downstream reads structured fields.
 *
 * Replace with a proper LLM router once the chat intent path stabilizes.
 */
object IntentExtractor {
    private const val MIN_ARTIST_KEY_LENGTH = 3

    fun extract(prompt: String?, rules: TasteRules, catalogArtists: List<String> = emptyList()): RoutingIntent {
        val raw = prompt?.trim().orEmpty()
        if (raw.isBlank()) return RoutingIntent()
        val lower = raw.lowercase()

        val language = when {
            "english" in lower || "英文" in lower -> "en"
            "mandarin" in lower || "国语" in lower || "普通话" in lower -> "zh-CN"
            "chinese" in lower || "中文" in lower -> "zh"
            "cantonese" in lower || "粤语" in lower -> "yue"
            else -> null
        }

        val energy = when {
            "not too energetic" in lower || "quiet" in lower || "soft" in lower || "calm" in lower -> "low"
            "energetic" in lower || "loud" in lower || "upbeat" in lower -> "high"
            else -> null
        }

        val routine = when {
            "coding" in lower || "code" in lower -> "late-night-coding"
            "commute" in lower -> "commute"
            "sleep" in lower || "winding down" in lower -> "wind-down"
            else -> null
        }

        val moodTag = when {
            "less sad" in lower -> "less-sad"
            "rain" in lower -> "rain"
            "sad" in lower -> "sad"
            "happy" in lower || "uplift" in lower -> "uplift"
            else -> null
        }

        val avoid = buildList {
            if ("less sad" in lower) add("too-sad")
            if ("less english" in lower) add("english-heavy")
        }

        val artists = detectArtists(raw, rules, catalogArtists)

        return RoutingIntent(
            language = language,
            energy = energy,
            routine = routine,
            moodTag = moodTag,
            avoid = avoid,
            artists = artists
        )
    }

    private fun detectArtists(prompt: String, rules: TasteRules, catalog: List<String>): List<String> {
        val promptKey = normalizeForMatch(prompt)
        if (promptKey.isBlank()) return emptyList()

        val aliasArtists = rules.artistAliases
            .filter { (alias, _) -> normalizeForMatch(alias) in promptKey }
            .flatMap { it.value }

        val catalogArtists = catalog
            .filter { artist ->
                val key = normalizeForMatch(artist)
                key.length >= MIN_ARTIST_KEY_LENGTH && key in promptKey
            }

        return (aliasArtists + catalogArtists)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}

internal fun normalizeForMatch(value: String): String =
    value.lowercase().replace(Regex("""[\s.'’\-_/()（）·]+"""), "")
