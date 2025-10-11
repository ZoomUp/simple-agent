package com.example.routes

import com.example.agent.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

fun Route.chatRoutes(openAiClient: OpenAiClient) {
    val logger = LoggerFactory.getLogger("ChatRoutes")

    post("/chat") {
        val request = call.receive<ChatRequest>()
        val message = request.message.trim()
        val strategy = request.strategy?.lowercase()
        val maxOut = request.maxOutputTokens

        if (message.isEmpty()) {
            logger.warn("Received empty message")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Message must not be blank."))
            return@post
        }

        logger.info("Incoming message: '{}' (strategy={}, maxOut={})", message.take(60), strategy, maxOut)

        val messages = listOf(
            ResponseMessage(
                role = "system",
                content = listOf(
                    MessageContent(text = "Ты дружелюбный помощник. Отвечай на русском, если это уместно.")
                )
            ),
            ResponseMessage(
                role = "user",
                content = listOf(MessageContent(text = message))
            )
        )

        val options = OpenAiClient.SendOptions(strategy = strategy, maxOutputTokens = maxOut)
        val resp = openAiClient.generateReplyWithMetrics(messages, options)
        call.respond(resp)
    }
}
