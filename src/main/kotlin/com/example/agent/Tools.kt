package com.example.agent

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    suspend fun execute(argumentsJson: String): String

    fun definition(): ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = parameters
    )
}

object GetTimeTool : AgentTool {
    override val name: String = "get_time"
    override val description: String = "Returns the current server time in ISO-8601 format (UTC)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        putJsonArray("required") {}
        put("additionalProperties", false)
    }

    override suspend fun execute(argumentsJson: String): String {
        val instant = Instant.now().atOffset(ZoneOffset.UTC)
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant)
    }
}

@Serializable
data class EchoArgs(val text: String)

object EchoTool : AgentTool {
    override val name: String = "echo"
    override val description: String = "Echoes back the provided text exactly."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to repeat back to the user.")
            }
        }
        putJsonArray("required") { add("text") }
        put("additionalProperties", false)
    }

    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun execute(argumentsJson: String): String {
        return try {
            val args = json.decodeFromString(EchoArgs.serializer(), argumentsJson)
            args.text
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid arguments: ${e.message}")
        }
    }
}

object ToolRegistry {
    private val tools: Map<String, AgentTool> = listOf(GetTimeTool, EchoTool).associateBy { it.name }
    fun definitions(): List<ToolDefinition> = tools.values.map { it.definition() }
    fun find(name: String): AgentTool? = tools[name]
}
