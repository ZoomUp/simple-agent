package com.example.agent

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis
import kotlin.math.ceil

private const val HF_ROUTER_URL = "https://router.huggingface.co/v1"

class HfClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(HfClient::class.java)

    @Serializable
    private data class ChatCompletionsRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double? = 0.0,
        val max_tokens: Int? = 700
    )

    suspend fun chat(
        modelId: String,
        messages: List<ChatMessage>,
        temperature: Double? = null
    ): ModelResult {
        var raw = ""
        var latency: Long = 0

        return try {
            val body = ChatCompletionsRequest(
                model = modelId,
                messages = messages,
                temperature = temperature,
                max_tokens = 700
            )

            latency = measureTimeMillis {
                raw = httpClient.post("$HF_ROUTER_URL/chat/completions") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                    setBody(body)
                }.bodyAsText()
            }

            val root = json.parseToJsonElement(raw).jsonObject

            root["error"]?.jsonObject?.let { err ->
                val msg = err["message"]?.jsonPrimitive?.content ?: "HF Router error"
                logger.warn("Model {} error: {}", modelId, msg)
                return ModelResult(modelId, "", -1, -1, -1, latency, null, msg)
            }

            val choices = root["choices"]?.jsonArray ?: JsonArray(emptyList())
            val replyText = if (choices.isNotEmpty()) {
                choices[0].jsonObject["message"]?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content ?: ""
            } else ""

            val usage = root["usage"]?.jsonObject
            val promptTok = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull?.toInt()
                ?: estimateTokens(messages.joinToString("\n") { it.content })
            val completionTok = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull?.toInt()
                ?: estimateTokens(replyText)
            val totalTok = usage?.get("total_tokens")?.jsonPrimitive?.longOrNull?.toInt()
                ?: (promptTok + completionTok)

            ModelResult(
                model = modelId,
                reply = replyText.trim(),
                promptTokens = promptTok,
                completionTokens = completionTok,
                totalTokens = totalTok,
                latencyMs = latency,
                costUsd = null,
                error = null
            )
        } catch (e: Exception) {
            logger.error("HF Router call failed for model {}", modelId, e)
            ModelResult(modelId, "", -1, -1, -1, latency, null, e.message ?: "exception")
        }
    }

    private fun estimateTokens(text: String): Int = ceil(text.length / 4.0).toInt()
}
