package com.example.agent

import kotlinx.serialization.Serializable

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

@Serializable
data class FirstResponseRequest(
    val model: String,
    val input: List<ResponseMessage>,
    val max_output_tokens: Int? = null
)

@Serializable
data class ChatRequest(
    val message: String,
    val strategy: String? = null,        // null | "truncate" | "summary"
    val maxOutputTokens: Int? = null     // например 300
)

@Serializable
data class ChatResponse(
    val reply: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val tokensEstimated: Boolean,        // true, если usage не пришёл, и мы оценили
    val latencyMs: Long,
    val truncated: Boolean,
    val summarized: Boolean
)

@Serializable
data class ErrorResponse(val error: String)
