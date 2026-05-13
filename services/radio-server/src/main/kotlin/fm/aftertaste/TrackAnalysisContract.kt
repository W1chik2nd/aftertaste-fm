package fm.aftertaste

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val scoreNames = listOf(
    "energy",
    "valence",
    "night",
    "coding",
    "skipRisk",
    "danceability",
    "acousticness",
    "speechiness",
    "instrumentalness",
    "liveness",
    "emotionalIntensity",
    "lyricalFocus",
    "mainstreamAppeal"
)

fun trackAnalysisResponseSchema(): JsonObject = buildJsonObject {
    put("type", "json_schema")
    put("name", "aftertaste_track_analysis")
    put("strict", true)
    put("schema", buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", required("language", "moodTags", "contextTags", "soundTags", "useTags", "scores", "notes", "needsReview"))
        put("properties", buildJsonObject {
            put("language", stringValueSchema())
            put("moodTags", tagArraySchema())
            put("contextTags", tagArraySchema())
            put("soundTags", tagArraySchema())
            put("useTags", tagArraySchema())
            put("scores", scoresSchema())
            put("notes", notesSchema())
            put("needsReview", buildJsonObject { put("type", "boolean") })
        })
    })
}

private fun stringValueSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", required("value", "confidence", "evidence"))
    put("properties", buildJsonObject {
        put("value", buildJsonObject { put("type", "string") })
        put("confidence", numberSchema())
        put("evidence", sourceArraySchema())
    })
}

private fun tagArraySchema(): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", required("tag", "confidence", "evidence"))
        put("properties", buildJsonObject {
            put("tag", buildJsonObject { put("type", "string") })
            put("confidence", numberSchema())
            put("evidence", sourceArraySchema())
        })
    })
}

private fun scoresSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", required(*scoreNames.toTypedArray()))
    put("properties", buildJsonObject {
        scoreNames.forEach { put(it, numberValueSchema()) }
    })
}

private fun numberValueSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", required("value", "confidence", "evidence"))
    put("properties", buildJsonObject {
        put("value", numberSchema())
        put("confidence", numberSchema())
        put("evidence", sourceArraySchema())
    })
}

private fun notesSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", required("summary", "evidence"))
    put("properties", buildJsonObject {
        put("summary", buildJsonObject { put("type", "string") })
        put("evidence", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", required("tag", "evidenceString"))
                put("properties", buildJsonObject {
                    put("tag", buildJsonObject { put("type", "string") })
                    put("evidenceString", buildJsonObject { put("type", "string") })
                })
            })
        })
    })
}

private fun sourceArraySchema(): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray {
            add("metadata")
            add("lyrics")
            add("playlist_context")
            add("model_inference")
        })
    })
}

private fun numberSchema(): JsonObject = buildJsonObject {
    put("type", "number")
    put("minimum", 0)
    put("maximum", 1)
}

private fun required(vararg names: String) = buildJsonArray {
    names.forEach { add(it) }
}
