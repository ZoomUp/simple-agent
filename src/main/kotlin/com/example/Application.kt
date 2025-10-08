package com.example

import com.example.agent.HfClient
import com.example.routes.multiChatRoutes
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
    val hfToken = System.getenv("HF_TOKEN")
        ?: throw IllegalStateException("HF_TOKEN environment variable is not set")

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val httpClient = HttpClient(Apache) {
        install(ContentNegotiation) { json(json) }
    }
    environment.monitor.subscribe(ApplicationStopped) { httpClient.close() }

    val hfClient = HfClient(httpClient, hfToken, json)

    install(ServerContentNegotiation) { json(json) }
    install(CallLogging)

    routing {
        staticResources("/", "static")
        multiChatRoutes(hfClient)
    }

    logger.info("HF Router client initialized")
}
