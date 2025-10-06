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
    val input: List<ResponseMessage>
)

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class ChatResponse(val reply: String)

@Serializable
data class ErrorResponse(val error: String)
