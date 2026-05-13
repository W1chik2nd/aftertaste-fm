package fm.aftertaste

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

object AtomicFiles {
    suspend fun writeString(path: Path, content: String) {
        withContext(Dispatchers.IO) {
            writeStringBlocking(path, content)
        }
    }

    fun writeStringBlocking(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        try {
            Files.move(part, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(part, path, REPLACE_EXISTING)
        }
    }
}
