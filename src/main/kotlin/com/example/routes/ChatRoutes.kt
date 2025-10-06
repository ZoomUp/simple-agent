package com.example.routes

import com.example.agent.ChatRequest
import com.example.agent.ChatResponse
import com.example.agent.ErrorResponse
import com.example.agent.MessageContent
import com.example.agent.OpenAiClient
import com.example.agent.ResponseMessage
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
                        text = "Ты дружелюбный помощник. Отвечай пользователю на русском языке, если это уместно."
                    )
                )
            ),
            ResponseMessage(
                role = "user",
                content = listOf(MessageContent(text = message))
            )
        )

        val reply = openAiClient.generateReply(messages).ifBlank {
            logger.warn("Received blank reply from model")
            "Извини, я не смог сформировать ответ."
        }

        logger.info("Final reply: {}", reply)
        call.respond(ChatResponse(reply = reply))
    }
}
