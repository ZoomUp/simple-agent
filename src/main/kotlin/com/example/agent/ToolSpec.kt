package com.example.agent

import kotlinx.serialization.SerialName
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
 * Формат tools для Responses API:
 * {
 *   "type": "function",
 *   "name": "...",
 *   "description": "...",
 *   "parameters": { JSON Schema }
 * }
 */
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class FirstResponseRequest(
    val model: String,
    val input: List<ResponseMessage>,
    val tools: List<ToolDefinition>
)

@Serializable
data class ToolOutputPayload(
    @SerialName("tool_call_id")
    val toolCallId: String,
    val output: String
)

/**
 * Элемент input для второго запроса.
 * Для Responses API нам нужен type="function_call_output"
 * с полями call_id и output.
 */
@Serializable
data class ContinueInput(
    val type: String,
    @SerialName("call_id")
    val callId: String? = null,
    val output: String? = null
)

/** Сам второй запрос (без mapOf, чтобы не ловить SerializationException) */
@Serializable
data class ContinueResponseRequest(
    val model: String,
    @SerialName("previous_response_id")
    val previousResponseId: String,
    val input: List<ContinueInput>
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class ParsedResponse(
    val responseId: String,
    val toolCalls: List<ToolCall>,
    val text: String?
)

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class ChatResponse(val reply: String)

@Serializable
data class ErrorResponse(val error: String)
