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

    suspend fun firstResponse(
        messages: List<ResponseMessage>,
        tools: List<ToolDefinition>
    ): ParsedResponse {
        val requestBody = FirstResponseRequest(
            model = model,
            input = messages,
            tools = tools
        )

        logger.info("Sending initial request to OpenAI with {} tool definitions", tools.size)

        val responseText = httpClient.post(RESPONSES_URL) {
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(requestBody)
        }.bodyAsText()

        logger.debug("First response raw payload: {}", responseText)
        return parseResponse(responseText)
    }

    suspend fun finalResponse(
        responseId: String,
        toolOutputs: List<ToolOutputPayload>
    ): ParsedResponse {
        // Responses API ждёт список function_call_output-элементов.
        val inputs = toolOutputs.map { tool ->
            ContinueInput(
                type = "function_call_output",
                callId = tool.toolCallId,
                output = tool.output
            )
        }

        // ВАЖНО: привязываем ко "вчерашнему" ответу через previous_response_id
        val requestBody = ContinueResponseRequest(
            model = model,
            previousResponseId = responseId,
            input = inputs
        )

        logger.info("Sending tool outputs for response {}", responseId)

        val responseText = httpClient.post(RESPONSES_URL) {
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(requestBody)
        }.bodyAsText()

        logger.debug("Final response raw payload: {}", responseText)

        return parseResponse(responseText)
    }

    private fun parseResponse(raw: String): ParsedResponse {
        val root = json.parseToJsonElement(raw).jsonObject
        val responseId = root["id"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Response did not contain an id")
        val output = root["output"]?.jsonArray ?: JsonArray(emptyList())

        val toolCalls = mutableListOf<ToolCall>()
        val texts = mutableListOf<String>()

        for (element in output) {
            collectFromOutputElement(element, toolCalls, texts)
        }

        if (texts.isEmpty()) {
            root["output_text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
        }

        val messageText = if (texts.isNotEmpty()) texts.joinToString("\n").trim() else null
        logger.info(
            "Parsed response {} with {} tool calls and text length {}",
            responseId,
            toolCalls.size,
            messageText?.length ?: 0
        )
        return ParsedResponse(responseId = responseId, toolCalls = toolCalls, text = messageText)
    }

    private fun collectFromOutputElement(
        element: JsonElement,
        toolCalls: MutableList<ToolCall>,
        texts: MutableList<String>
    ) {
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: ""
        when (type) {
            // Первый шаг: модель возвращает намерение вызвать функцию
            "function_call" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.content ?: "unknown_call"
                val name = obj["name"]?.jsonPrimitive?.content ?: return
                val arguments = obj["arguments"]?.jsonPrimitive?.content ?: "{}"
                toolCalls.add(ToolCall(id = callId, name = name, argumentsJson = arguments))
            }
            // Второй шаг: финальный текст обычно приходит как message/output_text
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
            // Поддержка старых/альтернативных форматов (на всякий случай)
            "tool_call", "response.tool_calls" -> {
                val toolCallId = obj["id"]?.jsonPrimitive?.content
                    ?: obj["tool_call_id"]?.jsonPrimitive?.content
                if (obj.containsKey("tool_calls")) {
                    val nested = obj["tool_calls"]?.jsonArray
                    if (nested != null) {
                        for (callElement in nested) {
                            parseToolCall(callElement, toolCallId, toolCalls)
                        }
                    }
                } else {
                    parseToolCall(element, toolCallId, toolCalls)
                }
            }
            else -> {
                if (obj.containsKey("tool_calls")) {
                    val nested = obj["tool_calls"]?.jsonArray
                    if (nested != null) {
                        for (callElement in nested) {
                            parseToolCall(callElement, null, toolCalls)
                        }
                    }
                }
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

    private fun parseToolCall(
        element: JsonElement,
        parentId: String?,
        toolCalls: MutableList<ToolCall>
    ) {
        val obj = element.jsonObject
        val functionObj = obj["function"]?.jsonObject ?: return
        val name = functionObj["name"]?.jsonPrimitive?.content ?: return
        val arguments = functionObj["arguments"]?.jsonPrimitive?.content ?: "{}"
        val callId = obj["id"]?.jsonPrimitive?.content
            ?: obj["tool_call_id"]?.jsonPrimitive?.content
            ?: parentId
            ?: name
        toolCalls.add(ToolCall(id = callId, name = name, argumentsJson = arguments))
    }
}

private fun JsonPrimitive?.contentOrNull(): String? = this?.let { primitive ->
    if (primitive.isString || primitive.booleanOrNull != null || primitive.longOrNull != null || primitive.doubleOrNull != null) {
        primitive.content
    } else null
}
