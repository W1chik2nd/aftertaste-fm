package fm.aftertaste

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class StateStore(private val path: Path = Path.of("data/state.json")) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): StoredState {
        return runCatching {
            if (!Files.exists(path)) return StoredState()
            json.decodeFromString<StoredState>(Files.readString(path))
        }.getOrElse { StoredState() }
    }

    fun save(state: StoredState) {
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(state))
        }
    }
}
