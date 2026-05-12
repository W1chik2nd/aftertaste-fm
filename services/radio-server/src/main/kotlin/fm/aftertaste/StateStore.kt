package fm.aftertaste

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime

class StateStore(
    private val path: Path = Env.path("STATE_DB_PATH", "services/radio-server/data/state.db"),
    private val legacyJsonPath: Path = Env.path("LEGACY_STATE_JSON_PATH", "services/radio-server/data/state.json")
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        initialize()
    }

    fun load(): StoredState = synchronized(this) {
        runCatching {
            readAppState() ?: importLegacyState() ?: StoredState()
        }.getOrElse { StoredState() }
    }

    fun save(state: StoredState) = synchronized(this) {
        runCatching {
            connection().use { db ->
                db.autoCommit = false
                upsertAppState(db, state)
                upsertPrefs(db, state.settings)
                state.showPlan?.let { upsertPlan(db, it) }
                db.commit()
            }
        }
    }

    fun rememberMessage(role: String, content: String) = synchronized(this) {
        val trimmed = content.trim().take(1200)
        if (trimmed.isBlank()) return@synchronized
        runCatching {
            connection().use { db ->
                db.prepareStatement(
                    """
                    insert into messages(role, content, created_at)
                    values (?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, role)
                    statement.setString(2, trimmed)
                    statement.setString(3, now())
                    statement.executeUpdate()
                }
            }
        }
    }

    fun rememberPlan(plan: ShowPlan) = synchronized(this) {
        runCatching {
            connection().use { db -> upsertPlan(db, plan) }
        }
    }

    fun rememberPlayback(action: String, item: QueueItem?) = synchronized(this) {
        runCatching {
            connection().use { db ->
                db.prepareStatement(
                    """
                    insert into plays(track_id, title, artist, provider, action, segment_id, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, item?.track?.id)
                    statement.setString(2, item?.track?.title)
                    statement.setString(3, item?.track?.artist)
                    statement.setString(4, item?.track?.provider)
                    statement.setString(5, action)
                    statement.setString(6, item?.segmentId)
                    statement.setString(7, now())
                    statement.executeUpdate()
                }
            }
        }
    }

    fun recentMemory(limit: Int = 8): List<String> = synchronized(this) {
        runCatching {
            connection().use { db ->
                val memories = mutableListOf<String>()
                db.prepareStatement(
                    """
                    select title, created_at
                    from plans
                    order by created_at desc
                    limit 2
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            memories += "recent_plan=${rows.getString("title")}"
                        }
                    }
                }
                db.prepareStatement(
                    """
                    select title, artist, action
                    from plays
                    where title is not null
                    order by created_at desc
                    limit 4
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            memories += "recent_${rows.getString("action")}=${rows.getString("title")} by ${rows.getString("artist")}"
                        }
                    }
                }
                db.prepareStatement(
                    """
                    select role, content
                    from messages
                    order by created_at desc
                    limit 3
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            val content = rows.getString("content").replace(Regex("\\s+"), " ").take(180)
                            memories += "recent_${rows.getString("role")}_message=$content"
                        }
                    }
                }
                memories.take(limit)
            }
        }.getOrElse { emptyList() }
    }

    private fun initialize() = synchronized(this) {
        Files.createDirectories(path.parent)
        connection().use { db ->
            db.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    create table if not exists app_state(
                        key text primary key,
                        value text not null,
                        updated_at text not null
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    create table if not exists messages(
                        id integer primary key autoincrement,
                        role text not null,
                        content text not null,
                        created_at text not null
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    create table if not exists plays(
                        id integer primary key autoincrement,
                        track_id text,
                        title text,
                        artist text,
                        provider text,
                        action text not null,
                        segment_id text,
                        created_at text not null
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    create table if not exists plans(
                        id text primary key,
                        title text not null,
                        plan_json text not null,
                        generated_at text,
                        created_at text not null
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    create table if not exists prefs(
                        key text primary key,
                        value text not null,
                        updated_at text not null
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun readAppState(): StoredState? =
        connection().use { db ->
            db.prepareStatement("select value from app_state where key = ?").use { statement ->
                statement.setString(1, "current")
                statement.executeQuery().use { rows ->
                    if (rows.next()) json.decodeFromString<StoredState>(rows.getString("value")) else null
                }
            }
        }

    private fun importLegacyState(): StoredState? {
        if (!Files.exists(legacyJsonPath)) return null
        return runCatching {
            json.decodeFromString<StoredState>(Files.readString(legacyJsonPath))
                .also { save(it) }
        }.getOrNull()
    }

    private fun upsertAppState(db: Connection, state: StoredState) {
        db.prepareStatement(
            """
            insert into app_state(key, value, updated_at)
            values ('current', ?, ?)
            on conflict(key) do update set
                value = excluded.value,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, json.encodeToString(state))
            statement.setString(2, now())
            statement.executeUpdate()
        }
    }

    private fun upsertPlan(db: Connection, plan: ShowPlan) {
        db.prepareStatement(
            """
            insert into plans(id, title, plan_json, generated_at, created_at)
            values (?, ?, ?, ?, ?)
            on conflict(id) do update set
                title = excluded.title,
                plan_json = excluded.plan_json,
                generated_at = excluded.generated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, plan.id)
            statement.setString(2, plan.title)
            statement.setString(3, json.encodeToString(plan))
            statement.setString(4, plan.generatedAt)
            statement.setString(5, now())
            statement.executeUpdate()
        }
    }

    private fun upsertPrefs(db: Connection, settings: UserSettings) {
        settings.weatherLocation?.let { upsertPref(db, "weather_location", it) }
        settings.weather?.let { upsertPref(db, "weather_snapshot", json.encodeToString(it)) }
    }

    private fun upsertPref(db: Connection, key: String, value: String) {
        db.prepareStatement(
            """
            insert into prefs(key, value, updated_at)
            values (?, ?, ?)
            on conflict(key) do update set
                value = excluded.value,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.setString(3, now())
            statement.executeUpdate()
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    private fun now(): String =
        OffsetDateTime.now().toString()
}
