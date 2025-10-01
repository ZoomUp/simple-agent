package com.example.routes

import com.example.agent.ChatRequest
import com.example.agent.ChatResponse
import com.example.agent.ErrorResponse
import com.example.agent.MessageContent
import com.example.agent.OpenAiClient
import com.example.agent.ResponseMessage
import com.example.agent.ToolOutputPayload
import com.example.agent.ToolRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

fun Route.chatRoutes(openAiClient: OpenAiClient) {
    val logger = LoggerFactory.getLogger("ChatRoutes")
    val tools = ToolRegistry

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
                        text = "You are a helpful Kotlin backend agent. Decide when to call tools."
                    )
                )
            ),
            ResponseMessage(
                role = "user",
                content = listOf(MessageContent(text = message))
            )
        )

        val firstResponse = openAiClient.firstResponse(messages, tools.definitions())
        logger.info(
            "OpenAI response {} with {} tool call(s)",
            firstResponse.responseId,
            firstResponse.toolCalls.size
        )

        if (firstResponse.toolCalls.isEmpty()) {
            val reply = firstResponse.text.orEmpty()
            logger.info("Final reply without tools: {}", reply)
            call.respond(ChatResponse(reply = reply))
            return@post
        }

        val toolOutputs = firstResponse.toolCalls.map { toolCall ->
            val tool = tools.find(toolCall.name)
            if (tool == null) {
                logger.error("Tool {} not found", toolCall.name)
                return@map ToolOutputPayload(
                    toolCallId = toolCall.id,
                    output = "Tool \"${toolCall.name}\" error: Tool not found."
                )
            }
            val output = runCatching {
                logger.info("Executing tool {} with arguments {}", tool.name, toolCall.argumentsJson)
                tool.execute(toolCall.argumentsJson)
            }.onFailure { throwable ->
                logger.error("Tool {} failed", tool.name, throwable)
            }.getOrElse { throwable ->
                "Tool \"${tool.name}\" error: ${throwable.message ?: "unknown error"}"
            }
            ToolOutputPayload(toolCallId = toolCall.id, output = output)
        }

        logger.info("Sending tool outputs back to OpenAI: {}", toolOutputs.map { it.toolCallId })

        val finalResponse = openAiClient.finalResponse(firstResponse.responseId, toolOutputs)
        val reply = finalResponse.text.orEmpty()
        logger.info("Final reply after tools: {}", reply)

        call.respond(ChatResponse(reply = reply))
    }
}
