package com.example.agent

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.system.measureTimeMillis

private const val RESPONSES_URL = "https://api.openai.com/v1/responses"

class OpenAiClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(OpenAiClient::class.java)

    private val contextLimit: Int =
        System.getenv("MODEL_CONTEXT_TOKENS")?.toIntOrNull() ?: 8192

    data class SendOptions(
        val strategy: String? = null,     // null | "truncate" | "summary"
        val maxOutputTokens: Int? = 300
    )

    suspend fun generateReplyWithMetrics(
        originalMessages: List<ResponseMessage>,
        options: SendOptions = SendOptions()
    ): ChatResponse {

        // 1) оценим токены промпта (грубо, chars/4)
        val promptTokEst = estimateTokensForMessages(originalMessages)

        // сколько резервируем под ответ
        val answerReserve = options.maxOutputTokens ?: 300

        // 2) если не влезает, применяем политику
        val needCompress = promptTokEst + answerReserve > contextLimit
        val (messagesToSend, truncated, summarized) =
            if (!needCompress || options.strategy.isNullOrBlank()) {
                Triple(originalMessages, false, false)
            } else {
                when (options.strategy) {
                    "truncate" -> compressByTruncate(originalMessages, promptTokEst, answerReserve)
                    "summary"  -> compressBySummary(originalMessages, answerReserve)
                    else       -> Triple(originalMessages, false, false)
                }
            }

        // 3) соберём тело запроса
        val requestBody = FirstResponseRequest(
            model = model,
            input = messagesToSend,
            max_output_tokens = options.maxOutputTokens
        )

        // 4) вызов API + замер времени
        var raw = ""
        val latency = measureTimeMillis {
            raw = httpClient.post(RESPONSES_URL) {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                setBody(requestBody)
            }.bodyAsText()
        }

        // 5) распарсим: текст и usage.*
        val root = json.parseToJsonElement(raw).jsonObject
        val output = root["output"]?.jsonArray ?: JsonArray(emptyList())
        val texts = mutableListOf<String>()
        for (element in output) collectFromOutputElement(element, texts)
        if (texts.isEmpty()) {
            root["output_text"]?.jsonPrimitive?.contentOrNull()?.let { texts.add(it) }
        }
        val replyText = texts.joinToString("\n").trim()

        val usage = root["usage"]?.jsonObject
        val promptTok = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull
            ?: usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull
        val completionTok = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull
            ?: usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull
        val totalTok = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull

        val tokensEstimated = usage == null
        val pTok = promptTok ?: estimateTokensForMessages(messagesToSend)
        val cTok = completionTok ?: estimateTokens(replyText)
        val tTok = totalTok ?: (pTok + cTok)

        logger.info(
            "usage: prompt={}, completion={}, total={}, estimated={}",
            pTok, cTok, tTok, tokensEstimated
        )

        return ChatResponse(
            reply = replyText,
            promptTokens = pTok,
            completionTokens = cTok,
            totalTokens = tTok,
            tokensEstimated = tokensEstimated,
            latencyMs = latency,
            truncated = truncated,
            summarized = summarized
        )
    }

    // ==== internal: parsing Helpers ====

    private fun collectFromOutputElement(element: JsonElement, texts: MutableList<String>) {
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

    // ==== token estimation & compression ====

    private fun estimateTokens(text: String): Int =
        ceil(text.length / 4.0).toInt()

    private fun estimateTokensForMessages(msgs: List<ResponseMessage>): Int =
        msgs.sumOf { m -> m.content.sumOf { estimateTokens(it.text) } }

    /**
     * Truncate-политика: оставляем system целиком, у user берём последние ~N символов,
     * пока не усядемся в (contextLimit - reserve).
     */
    private fun compressByTruncate(
        original: List<ResponseMessage>,
        promptTokEst: Int,
        answerReserve: Int
    ): Triple<List<ResponseMessage>, Boolean, Boolean> {
        val room = contextLimit - answerReserve
        if (promptTokEst <= room) return Triple(original, false, false)

        val systemParts = original.filter { it.role == "system" }
        val nonSystem = original.filter { it.role != "system" }

        val mergedUser = nonSystem.joinToString("\n") { rm ->
            rm.content.joinToString("\n") { it.text }
        }

        // Сколько токенов можно оставить
        val allowed = (room * 0.9).toInt().coerceAtLeast(256) // чуть запас
        val keepChars = (allowed * 4)                          // обратная оценка

        val tail = if (mergedUser.length > keepChars)
            "…" + mergedUser.takeLast(keepChars)
        else mergedUser

        val newUser = ResponseMessage(
            role = "user",
            content = listOf(MessageContent(text = tail))
        )
        val result = systemParts + newUser
        return Triple(result, true, false)
    }

    /**
     * Summary-политика (лайт): оставляем system, а user ужимаем до конспекта-маркдауна
     * - просто берём первые 2000 символов + последние 2000 символов, вставляем маркер.
     * Это «псевдо-summary», но демонстрирует механику.
     */
    private fun compressBySummary(
        original: List<ResponseMessage>,
        answerReserve: Int
    ): Triple<List<ResponseMessage>, Boolean, Boolean> {
        val systemParts = original.filter { it.role == "system" }
        val nonSystem = original.filter { it.role != "system" }

        val mergedUser = nonSystem.joinToString("\n") { rm ->
            rm.content.joinToString("\n") { it.text }
        }

        val head = mergedUser.take(2000)
        val tail = mergedUser.takeLast(2000)
        val pseudoSummary = buildString {
            appendLine("Краткий конспект:")
            appendLine(head)
            if (mergedUser.length > 4000) {
                appendLine("\n…\n")
                appendLine(tail)
            }
        }

        val newUser = ResponseMessage(
            role = "user",
            content = listOf(MessageContent(text = pseudoSummary))
        )
        val result = systemParts + newUser

        // Если всё ещё длинно — применим дополнительный truncate
        val promptTok = estimateTokensForMessages(result)
        return if (promptTok + answerReserve > contextLimit) {
            compressByTruncate(result, promptTok, answerReserve).copy(third = true)
        } else Triple(result, false, true)
    }
}

private fun JsonPrimitive?.contentOrNull(): String? = this?.let { p ->
    if (p.isString || p.booleanOrNull != null || p.longOrNull != null || p.doubleOrNull != null) p.content else null
}
