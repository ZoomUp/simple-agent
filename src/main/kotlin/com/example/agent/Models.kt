package com.example.agent

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,      // "system" | "user" | "assistant"
    val content: String
)

@Serializable
data class MultiChatRequest(
    val message: String,
    val temperature: Double? = null,
    val models: List<String>? = null
)

@Serializable
data class ModelResult(
    val model: String,
    val reply: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val latencyMs: Long,
    val costUsd: Double? = null,
    val error: String? = null
)

@Serializable
data class MultiChatResponse(
    val results: List<ModelResult>
)

@Serializable
data class MessageContent(
    val type: String = "input_text",
    val text: String
)

@Serializable
data class ResponseMessage(
    val role: String,                    // "system" | "user" | "assistant"
    val content: List<MessageContent>
)


@Serializable
data class ErrorResponse(val error: String)
