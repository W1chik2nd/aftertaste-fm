package fm.aftertaste

import java.nio.file.Files
import java.nio.file.Path

object Env {
    private val fileValues: Map<String, String> by lazy { loadDotEnv() }
    private val dotEnvPath: Path? by lazy { findDotEnv() }

    fun value(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: fileValues[name]?.takeIf { it.isNotBlank() }

    fun projectRoot(): Path =
        dotEnvPath?.parent ?: findProjectRoot() ?: Path.of("").toAbsolutePath()

    fun path(name: String, defaultRelativePath: String): Path {
        val configured = value(name)
        return if (configured.isNullOrBlank()) {
            projectRoot().resolve(defaultRelativePath).normalize()
        } else {
            Path.of(configured).let { path ->
                if (path.isAbsolute) path else projectRoot().resolve(path).normalize()
            }
        }
    }

    private fun loadDotEnv(): Map<String, String> {
        val path = dotEnvPath ?: return emptyMap()
        return Files.readAllLines(path)
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || "=" !in trimmed) {
                    null
                } else {
                    val key = trimmed.substringBefore("=").trim()
                    val rawValue = trimmed.substringAfter("=").trim()
                    key to rawValue.trim('"', '\'')
                }
            }
            .toMap()
    }

    private fun findDotEnv(): Path? {
        val candidates = generateSequence(Path.of("").toAbsolutePath()) { current ->
            current.parent
        }.take(6)
        return candidates
            .map { it.resolve(".env") }
            .firstOrNull { Files.exists(it) }
    }

    private fun findProjectRoot(): Path? {
        val candidates = generateSequence(Path.of("").toAbsolutePath()) { current ->
            current.parent
        }.take(6)
        return candidates.firstOrNull { path ->
            Files.exists(path.resolve("package.json")) && Files.exists(path.resolve("AGENTS.md"))
        }
    }
}
