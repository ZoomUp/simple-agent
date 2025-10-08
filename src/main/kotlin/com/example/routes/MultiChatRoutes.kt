package com.example.routes

import com.example.agent.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

fun Route.multiChatRoutes(hfClient: HfClient) {
    val logger = LoggerFactory.getLogger("MultiChatRoutes")

    // Дефолтная тройка моделей (hosted/Router)
    val defaultModels = listOf(
        "katanemo/Arch-Router-1.5B:hf-inference",      // small
        "HuggingFaceTB/SmolLM3-3B:hf-inference",       // mid
        "Qwen/Qwen2-7B-Instruct:featherless-ai"        // large-ish (через провайдера)
    )

    post("/multi-chat") {
        val req = call.receive<MultiChatRequest>()
        val message = req.message.trim()
        val temperature = req.temperature
        val models = req.models?.takeIf { it.isNotEmpty() } ?: defaultModels

        if (message.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Message must not be blank."))
            return@post
        }

        logger.info("Multi-chat request: '{}' on models {}", message, models)

        val chatMessages = listOf(
            ChatMessage(role = "system", content = "You are a helpful assistant. Answer in Russian if appropriate."),
            ChatMessage(role = "user", content = message)
        )

        val results = coroutineScope {
            models.map { modelId ->
                async { hfClient.chat(modelId, chatMessages, temperature) }
            }.awaitAll()
        }

        call.respond(MultiChatResponse(results))
    }
}
