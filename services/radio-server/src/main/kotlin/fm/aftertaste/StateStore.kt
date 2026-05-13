package fm.aftertaste

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

class StateStore(
    private val path: Path = Env.path("STATE_DB_PATH", "services/radio-server/data/state.db")
) {
    private val logger = LoggerFactory.getLogger(StateStore::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val database: Database

    init {
        Files.createDirectories(path.parent)
        database = Database.connect(
            url = "jdbc:sqlite:${path.toAbsolutePath()}",
            driver = "org.sqlite.JDBC"
        )
        transaction(database) {
            SchemaUtils.create(AppState, Messages, Plays, Plans)
        }
    }

    /**
     * Synchronous load is intentional: called once from `RadioEngine.init`, where coroutines
     * aren't available. Subsequent reads/writes are `suspend` and dispatched on Dispatchers.IO.
     */
    fun loadBlocking(): StoredState =
        runCatching {
            transaction(database) {
                AppState.selectAll()
                    .where { AppState.key eq APP_STATE_KEY }
                    .limit(1)
                    .firstOrNull()
                    ?.let { json.decodeFromString<StoredState>(it[AppState.value]) }
            } ?: StoredState()
        }.getOrElse {
            logger.warn("StateStore.loadBlocking failed: {}", it.message)
            StoredState()
        }

    suspend fun save(state: StoredState) = withDb {
        AppState.upsert {
            it[key] = APP_STATE_KEY
            it[value] = json.encodeToString(state)
            it[updatedAt] = now()
        }
        state.showPlan?.let { upsertPlan(it) }
    }

    suspend fun rememberMessage(role: String, content: String) {
        val trimmed = content.trim().take(MESSAGE_MAX_CHARS)
        if (trimmed.isBlank()) return
        withDb {
            Messages.insert {
                it[Messages.role] = role
                it[Messages.content] = trimmed
                it[createdAt] = now()
            }
        }
    }

    suspend fun rememberPlan(plan: ShowPlan) = withDb { upsertPlan(plan) }

    suspend fun rememberPlayback(action: String, item: QueueItem?) = withDb {
        Plays.insert {
            it[trackId] = item?.track?.id
            it[title] = item?.track?.title
            it[artist] = item?.track?.artist
            it[provider] = item?.track?.provider
            it[Plays.action] = action
            it[segmentId] = item?.segmentId
            it[createdAt] = now()
        }
    }

    suspend fun recentMemory(): ContextMemory =
        runCatching {
            newSuspendedTransaction(Dispatchers.IO, database) {
                val plans = Plans.selectAll()
                    .orderBy(Plans.createdAt, SortOrder.DESC)
                    .limit(RECENT_PLANS)
                    .map { it[Plans.title] }
                val plays = Plays.selectAll()
                    .where { Plays.title.isNotNull() }
                    .orderBy(Plays.createdAt, SortOrder.DESC)
                    .limit(RECENT_PLAYS)
                    .map {
                        RecentPlay(
                            action = it[Plays.action],
                            title = it[Plays.title] ?: "",
                            artist = it[Plays.artist]
                        )
                    }
                val messages = Messages.selectAll()
                    .orderBy(Messages.createdAt, SortOrder.DESC)
                    .limit(RECENT_MESSAGES)
                    .map {
                        RecentMessage(
                            role = it[Messages.role],
                            content = it[Messages.content].replace(WHITESPACE_REGEX, " ").take(MESSAGE_SNIPPET)
                        )
                    }
                ContextMemory(recentPlans = plans, recentPlays = plays, recentMessages = messages)
            }
        }.getOrElse {
            logger.warn("StateStore.recentMemory failed: {}", it.message)
            ContextMemory()
        }

    private fun upsertPlan(plan: ShowPlan) {
        Plans.upsert {
            it[id] = plan.id
            it[title] = plan.title
            it[planJson] = json.encodeToString(plan)
            it[generatedAt] = plan.generatedAt
            it[createdAt] = now()
        }
    }

    private suspend fun withDb(block: () -> Unit) {
        runCatching {
            newSuspendedTransaction(Dispatchers.IO, database) { block() }
        }.onFailure { logger.warn("StateStore op failed: {}", it.message) }
    }

    private fun now(): String = OffsetDateTime.now().toString()

    companion object {
        private const val APP_STATE_KEY = "current"
        private const val MESSAGE_MAX_CHARS = 1200
        private const val MESSAGE_SNIPPET = 180
        private const val RECENT_PLANS = 2
        private const val RECENT_PLAYS = 4
        private const val RECENT_MESSAGES = 3
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
