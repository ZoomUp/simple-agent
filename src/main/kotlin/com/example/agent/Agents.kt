package com.example.agent

import org.slf4j.LoggerFactory
import kotlin.system.measureTimeMillis

/**
 * Два «классических» агента поверх твоего OpenAiClient:
 * - AgentOneSolver: формирует структурированный черновик решения (JSON)
 * - AgentTwoReviewer: проверяет черновик, даёт вердикт/замечания/исправление (JSON)
 */

data class AgentCallResult(
    val text: String,
    val latencyMs: Long
)

class AgentOneSolver(private val llm: OpenAiClient) {
    private val logger = LoggerFactory.getLogger(AgentOneSolver::class.java)

    suspend fun solve(task: String): AgentCallResult {
        val messages = listOf(
            ResponseMessage(
                role = "system",
                content = listOf(
                    MessageContent(
                        text =
                            """
                            Ты Агент 1 (Solver). Твоя задача — решить задачу и выдать короткий, структурированный результат в JSON.
                            Формат ответа строго (без лишнего текста до/после):
                            {
                              "answer": "<итог одним значением/строкой>",
                              "reasoning": ["краткий шаг 1", "краткий шаг 2", "…"]
                            }
                            Не выходи за пределы JSON. Не добавляй комментарии.
                            """.trimIndent()
                    )
                )
            ),
            ResponseMessage(
                role = "user",
                content = listOf(MessageContent(text = task))
            )
        )
        var text = ""
        val dt = measureTimeMillis {
            text = llm.generateReply(messages)
        }
        logger.info("Agent1 (solver) done in {} ms", dt)
        return AgentCallResult(text = text, latencyMs = dt)
    }
}

class AgentTwoReviewer(private val llm: OpenAiClient) {
    private val logger = LoggerFactory.getLogger(AgentTwoReviewer::class.java)

    suspend fun review(task: String, draftJson: String): AgentCallResult {
        val messages = listOf(
            ResponseMessage(
                role = "system",
                content = listOf(
                    MessageContent(
                        text =
                            """
                            Ты Агент 2 (Reviewer).
                            Проверь по чек-листу:
                            - 5 вариантов, каждый ≤ 12 слов
                            - дружелюбный тон, без вины пользователя, без жаргона
                            - без повтора формулировок/лексики
                            - кратко и понятно
                            Если нарушен хотя бы один пункт — verdict="fail", issues с перечислением нарушений, corrected_answer — исправленный результат (5 вариантов).
                            Строго JSON, без лишнего текста:
                            {"verdict":"pass|fail","issues":["..."],"corrected_answer":"<если fail — новый список, иначе пусто>"}

                            """.trimIndent()
                    )
                )
            ),
            ResponseMessage(
                role = "user",
                content = listOf(
                    MessageContent(
                        text = buildString {
                            appendLine("ЗАДАЧА:")
                            appendLine(task)
                            appendLine()
                            appendLine("ЧЕРНОВИК ОТ АГЕНТА 1 (JSON):")
                            appendLine(draftJson.trim())
                        }
                    )
                )
            )
        )
        var text = ""
        val dt = measureTimeMillis {
            text = llm.generateReply(messages)
        }
        logger.info("Agent2 (reviewer) done in {} ms", dt)
        return AgentCallResult(text = text, latencyMs = dt)
    }
}
