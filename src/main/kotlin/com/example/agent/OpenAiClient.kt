package com.example.agent

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Instant

private const val RESPONSES_URL = "https://api.openai.com/v1/responses"

class OpenAiClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(OpenAiClient::class.java)

    suspend fun generateReply(messages: List<ResponseMessage>): ChatResponse {
        val requestBody = FirstResponseRequest(
            model = model,
            input = messages,
            text = TEXT_WITH_SCHEMA   // требуем json_schema для {answer,topic,confidence,suggest}
        )

        logger.info("Sending chat request to OpenAI with {} messages", messages.size)

        val responseText = httpClient.post(RESPONSES_URL) {
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(requestBody)
        }.bodyAsText()

        logger.info("LLM raw response (truncated): {}", truncateForLog(responseText))
        return parseResponse(responseText)
    }

    private fun parseResponse(raw: String): ChatResponse {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val output = root["output"]?.jsonArray ?: JsonArray(emptyList())

            val texts = mutableListOf<String>()
            for (element in output) {
                collectTextFromOutput(element, texts)
            }

            if (texts.isEmpty()) {
                root["output_text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
            }

            val messageText = texts.joinToString(separator = "").trim()

            if (messageText.isBlank()) {
                logger.info("LLM parsing status: fallback (blank message)")
                fallback(raw)
            } else {
                parseStructuredOrFallback(messageText)
            }
        } catch (ex: Exception) {
            logger.warn("Failed to parse model response JSON", ex)
            logger.info("LLM parsing status: fallback")
            fallback(raw)
        }
    }

    private fun parseStructuredOrFallback(messageText: String): ChatResponse {
        return try {
            val payload   = json.parseToJsonElement(messageText).jsonObject
            val answer    = payload["answer"]?.jsonPrimitive?.content
            val topic     = payload["topic"]?.jsonPrimitive?.content
            val confidence= payload["confidence"]?.jsonPrimitive?.doubleOrNull
            val suggest   = payload["suggest"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull() } ?: emptyList()

            if (answer != null && topic != null && confidence != null) {
                logger.info("LLM parsing status: OK parsed")
                ChatResponse(
                    answer = answer,
                    topic = topic,
                    confidence = confidence.coerceIn(0.0, 1.0),
                    suggest = suggest
                )
            } else {
                fallback(messageText)
            }
        } catch (ex: Exception) {
            logger.debug("Structured JSON parse failed: {}", ex.message)
            logger.info("LLM parsing status: fallback")
            fallback(messageText)
        }
    }

    private fun fallback(text: String): ChatResponse = ChatResponse(
        answer = text.ifBlank { "(пусто)" },
        topic = "general",
        confidence = 0.5,
        suggest = emptyList(),
    )

    private fun truncateForLog(text: String, maxLength: Int = 800): String {
        return if (text.length <= maxLength) text else text.take(maxLength) + "…"
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
                    when (contentType) {
                        "output_text", "text" -> {
                            contentObj["text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
                        }

                        "output_json" -> appendJsonContent(contentObj["json"], texts)
                    }
                }
            }

            else -> {
                val contentArray = obj["content"]?.jsonArray
                if (contentArray != null) {
                    for (contentElement in contentArray) {
                        val contentObj = contentElement.jsonObject
                        val contentType = contentObj["type"]?.jsonPrimitive?.content ?: ""
                        when (contentType) {
                            "output_text", "text" -> {
                                contentObj["text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
                            }

                            "output_json" -> appendJsonContent(contentObj["json"], texts)
                        }
                    }
                }
            }
        }
    }

    private fun appendJsonContent(element: JsonElement?, texts: MutableList<String>) {
        when (element) {
            null -> return
            is JsonPrimitive -> element.contentOrNull()?.let { texts.add(it) }
            is JsonObject -> texts.add(element.toString())
            is JsonArray -> texts.add(element.toString())
            else -> texts.add(element.toString())
        }
    }

    companion object {
        private val STRUCTURED_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("answer")     { put("type", "string") }
                putJsonObject("topic")      { put("type", "string") }
                putJsonObject("confidence") { put("type", "number") }
                putJsonObject("suggest") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") {
                add("answer"); add("topic"); add("confidence"); add("suggest")
            }
            put("additionalProperties", JsonPrimitive(false))
        }

        private val TEXT_WITH_SCHEMA: TextConfig = TextConfig(
            format = TextFormat(
                type = "json_schema",
                name = "answer_with_source",
                schema = STRUCTURED_SCHEMA,
                strict = true
            ),
            verbosity = "medium"
        )
    }
}

private fun JsonPrimitive?.contentOrNull(): String? = this?.let { primitive ->
    if (
        primitive.isString ||
        primitive.booleanOrNull != null ||
        primitive.longOrNull != null ||
        primitive.doubleOrNull != null
    ) {
        primitive.content
    } else null
}
