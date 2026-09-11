package com.yangz3720.simplywork.infrastructure.model.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Standard tool definition exposed to the model.
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
    val isStrict: Boolean = false
)

/**
 * Single tool call request issued by the model.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
) {
    /**
     * Helper to decode the raw JSON arguments into a typed Kotlin model.
     */
    fun <T> decodeArguments(json: Json, serializer: KSerializer<T>): Result<T> = runCatching {
        json.decodeFromString(serializer, argumentsJson)
    }
}
