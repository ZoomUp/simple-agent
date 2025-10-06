package com.example

import com.example.agent.OpenAiClient
import com.example.routes.chatRoutes
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val logger = LoggerFactory.getLogger("AgentApplication")
    logger.info("Starting server on port {}", port)

    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("AgentModule")
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: throw IllegalStateException("OPENAI_API_KEY environment variable is not set")
    val model = System.getenv("OPENAI_MODEL") ?: "gpt-4.1"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val httpClient = HttpClient(Apache) {
        install(ContentNegotiation) { json(json) }
    }

    environment.monitor.subscribe(ApplicationStopped) { httpClient.close() }

    val openAiClient = OpenAiClient(httpClient, apiKey, model, json)

    install(ServerContentNegotiation) { json(json) }
    install(CallLogging)

    routing {
        // раздаём все файлы из resources/static по корню
        staticResources("/", "static")
        // твой API
        chatRoutes(openAiClient)
    }

    logger.info("Agent module initialized with model {}", model)
}
