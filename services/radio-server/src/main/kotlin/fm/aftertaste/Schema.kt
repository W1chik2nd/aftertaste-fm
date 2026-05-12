package fm.aftertaste

import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definitions. Columns intentionally match the existing SQLite schema 1:1 —
 * `SchemaUtils.create` issues `CREATE TABLE IF NOT EXISTS` so existing data is preserved.
 *
 * `created_at` columns stay as TEXT (ISO-8601 strings) for compatibility with rows written
 * by the prior raw-JDBC version. Migrate to a datetime column type when we need range queries.
 */

object AppState : Table("app_state") {
    val key = text("key")
    val value = text("value")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(key)
}

object Messages : Table("messages") {
    val id = integer("id").autoIncrement()
    val role = text("role")
    val content = text("content")
    val createdAt = text("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Plays : Table("plays") {
    val id = integer("id").autoIncrement()
    val trackId = text("track_id").nullable()
    val title = text("title").nullable()
    val artist = text("artist").nullable()
    val provider = text("provider").nullable()
    val action = text("action")
    val segmentId = text("segment_id").nullable()
    val createdAt = text("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Plans : Table("plans") {
    val id = text("id")
    val title = text("title")
    val planJson = text("plan_json")
    val generatedAt = text("generated_at").nullable()
    val createdAt = text("created_at")
    override val primaryKey = PrimaryKey(id)
}
