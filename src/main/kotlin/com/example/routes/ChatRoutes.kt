package com.example.routes

import com.example.agent.ChatRequest
import com.example.agent.MessageContent
import com.example.agent.OpenAiClient
import com.example.agent.ResponseMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import com.example.agent.ErrorResponse
import org.slf4j.LoggerFactory

fun Route.chatRoutes(openAiClient: OpenAiClient) {
    val logger = LoggerFactory.getLogger("ChatRoutes")

    post("/chat") {
        val request = call.receive<ChatRequest>()
        val message = request.message.trim()
        if (message.isEmpty()) {
            logger.warn("Received empty message")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Message must not be blank."))
            return@post
        }

        logger.info("Incoming message: {}", message)

        val messages = listOf(
            ResponseMessage(
                role = "system",
                content = listOf(
                    MessageContent(
                        text = """
                            Ты дружелюбный помощник. Отвечай пользователю на русском языке, если это уместно.
                            ВАЖНО: возвращай СТРОГО объект JSON со СТРОГО следующими полями и типами:
                            {
                              "answer": string,
                              "topic": string,                           // например: time|weather|money|general|travel|tech
                              "confidence": number,                      // 0..1
                              "suggest": string[],                       // список подсказок для пользователя для будущих вопросов
                            }
                            Ничего вне JSON не выводи.
                        """.trimIndent()
                    )
                )
            ),
            ResponseMessage(
                role = "user",
                content = listOf(MessageContent(text = message))
            )
        )

        val reply = openAiClient.generateReply(messages)

        logger.info(
            "Final answer: '{}' | topic={} | confidence={} | suggestCount={}",
            reply.answer, reply.topic, reply.confidence, reply.suggest.size
        )
        call.respond(reply)
    }
}
