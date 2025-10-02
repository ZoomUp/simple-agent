package com.example.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MessageContent(
    val type: String = "input_text",
    val text: String
)

@Serializable
data class ResponseMessage(
    val role: String,
    val content: List<MessageContent>
)

/**
 * Настройки форматированного текстового вывода Responses API (новый способ).
 * Мы требуем json_schema со строгой схемой.
 */
@Serializable
data class TextFormat(
    val type: String,
    val name: String? = null,
    val schema: JsonObject? = null,
    val strict: Boolean? = null
)

@Serializable
data class TextConfig(
    val format: TextFormat? = null,
    val verbosity: String? = null
)

@Serializable
data class FirstResponseRequest(
    val model: String,
    val input: List<ResponseMessage>,
    val text: TextConfig? = null
)

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class ChatResponse(
    val answer: String,
    val topic: String,
    val confidence: Double,
    val suggest: List<String>
)

@Serializable
data class ErrorResponse(val error: String)
