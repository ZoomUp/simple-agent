package com.example.agent

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private const val RESPONSES_URL = "https://api.openai.com/v1/responses"

class OpenAiClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(OpenAiClient::class.java)

    suspend fun generateReply(messages: List<ResponseMessage>): String {
        val requestBody = FirstResponseRequest(
            model = model,
            input = messages,
            temperature = 0.2,
            top_p = 0.9,
            max_output_tokens = 800
        )

        logger.info("Sending chat request to OpenAI with {} messages", messages.size)

        val responseText = httpClient.post(RESPONSES_URL) {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
            setBody(requestBody)
        }.bodyAsText()

        logger.debug("Chat response raw payload: {}", responseText)
        return parseResponse(responseText)
    }

    private fun parseResponse(raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val responseId = root["id"]?.jsonPrimitive?.content ?: "unknown"
        val output = root["output"]?.jsonArray ?: JsonArray(emptyList())

        val texts = mutableListOf<String>()
        for (element in output) collectTextFromOutput(element, texts)
        if (texts.isEmpty()) {
            root["output_text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
        }

        val messageText = texts.joinToString("\n").trim()
        logger.info("Parsed response {} with text length {}", responseId, messageText.length)
        return messageText
    }

    private fun collectTextFromOutput(
        element: JsonElement,
        texts: MutableList<String>
    ) {
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: ""
        when (type) {
            "message", "response.message" -> {
                val contentArray = obj["content"]?.jsonArray ?: return
                for (contentElement in contentArray) {
                    val contentObj = contentElement.jsonObject
                    val contentType = contentObj["type"]?.jsonPrimitive?.content ?: ""
                    if (contentType == "output_text" || contentType == "text") {
                        contentObj["text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
                    }
                }
            }
            else -> {
                val contentArray = obj["content"]?.jsonArray
                if (contentArray != null) {
                    for (contentElement in contentArray) {
                        val contentObj = contentElement.jsonObject
                        val contentType = contentObj["type"]?.jsonPrimitive?.content ?: ""
                        if (contentType == "output_text" || contentType == "text") {
                            contentObj["text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
                        }
                    }
                }
            }
        }
    }
}

private fun JsonPrimitive?.contentOrNull(): String? = this?.let { p ->
    if (p.isString || p.booleanOrNull != null || p.longOrNull != null || p.doubleOrNull != null) p.content else null
}
