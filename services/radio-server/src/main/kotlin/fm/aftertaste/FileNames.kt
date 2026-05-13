package fm.aftertaste

private const val FILE_STEM_MAX_CHARS = 96

/**
 * Keep generated filenames human-recognizable while staying portable across local filesystems.
 */
fun safeFileStem(value: String): String {
    val builder = StringBuilder(value.length)
    var lastDash = false
    value.lowercase().forEach { ch ->
        val keep = ch.isDigit() || (ch in 'a'..'z') ||
            ch in '぀'..'ゟ' || ch in '゠'..'ヿ' ||
            ch in '가'..'힯' || isCjkUnified(ch)
        if (keep) {
            builder.append(ch)
            lastDash = false
        } else if (!lastDash) {
            builder.append('-')
            lastDash = true
        }
    }
    return builder.toString().trim('-').take(FILE_STEM_MAX_CHARS).ifBlank { "item" }
}

fun isCjkUnified(ch: Char): Boolean =
    ch in '㐀'..'䶿' || ch in '一'..'鿿' || ch in '豈'..'﫿'
