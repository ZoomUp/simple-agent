package com.example.routes

import com.example.agent.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Один ввод → Agent1.solve → Agent2.review → ответ с двумя частями.
 */

@Serializable
data class PipelineRequest(val task: String)

@Serializable
data class PipelineResponse(
    val task: String,
    val agent1: Part,
    val agent2: Part
) {
    @Serializable
    data class Part(
        val text: String,
        val latencyMs: Long
    )
}

fun Route.pipelineRoutes(openAiClient: OpenAiClient) {
    val logger = LoggerFactory.getLogger("PipelineRoutes")
    val solver = AgentOneSolver(openAiClient)
    val reviewer = AgentTwoReviewer(openAiClient)

    post("/pipeline") {
        val req = call.receive<PipelineRequest>()
        val task = req.task.trim()
        if (task.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("task must not be blank"))
            return@post
        }

        logger.info("Pipeline start for task: {}", task)

        // 1) Агент 1 — черновик JSON
        val draft = solver.solve(task)

        // 2) Агент 2 — проверка черновика
        val check = reviewer.review(task, draft.text)

        val resp = PipelineResponse(
            task = task,
            agent1 = PipelineResponse.Part(text = draft.text, latencyMs = draft.latencyMs),
            agent2 = PipelineResponse.Part(text = check.text, latencyMs = check.latencyMs)
        )
        call.respond(resp)
    }
}
