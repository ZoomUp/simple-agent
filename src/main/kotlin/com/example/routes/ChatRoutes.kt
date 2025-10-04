package com.example.routes

import com.example.agent.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val SESSIONS = ConcurrentHashMap<String, MutableList<ResponseMessage>>()
private const val MAX_QUESTIONS = 5
private const val FINAL_MARKER = "<<FINAL>>"

fun Route.chatRoutes(openAiClient: OpenAiClient) {
    val logger = LoggerFactory.getLogger("ChatRoutes")

    post("/chat") {
        val req = call.receive<ChatRequest>()
        val message = req.message.trim()
        if (message.isEmpty()) {
            logger.warn("Received empty message")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Message must not be blank."))
            return@post
        }

        // sessionId: если фронт не прислал — делаем краткоживущую эпизодическую
        val sessionId = (req.sessionId ?: "ephemeral")
        val history = SESSIONS.computeIfAbsent(sessionId) { mutableListOf() }

        logger.info("Incoming message (sid={}): {}", sessionId, message)

        // Базовый system-prompt (границы темы + процесс)
        val system = ResponseMessage(
            role = "system",
            content = listOf(
                MessageContent(
                    text = """
        Ты — фасилитатор требований для мини-фичи. Разрешённая тема: сбор вводных и финальное ТЗ.

        Оффтоп:
        - Отклоняй ТОЛЬКО явный оффтоп (погода, новости, шутки, философия, код вне контекста фичи и т.п.).
        - Короткие одно- или двухсловные ответы по теме (напр. «локализация», «бэкенд») считай валидными. Прими их и переходи к следующему слоту.

        Процесс интервью (строго):
        - Первый вопрос ТОЛЬКО если не распознано название фичи и платформа:
          «Как называется фича и на какой платформе (iOS/Android/Web/Backend)?»
        - Далее приоритет слотов, по одному короткому вопросу:
          1) Основной сценарий (1 фраза)
          2) Данные/интеграции (источники, поля/идентификаторы)
          3) Ограничения/НФТ (время отклика, оффлайн/латентность, локализация)
          4) Критерии приёмки (2–3 проверяемых пункта)
        - Анти-повторы: не спрашивай то, что уже названо. Если неясно — уточни в 1 строку.
        - Максимум 5 вопросов. Если собраны ключевые поля ИЛИ пользователь пишет «готово/достаточно» — выдай финал.
        - Формулируй вопросы без вводных, одним предложением. Не предлагай множественный выбор (кроме первого о платформе).

        Формат финала (Markdown, ≤1200 символов):
        # ТЗ: <краткое имя фичи>
        **Цель:** …
        **Пользователь/Контекст:** …
        **Сценарии:**
        - …
        - …
        **Данные/Интеграции:** …
        **Ограничения/НФТ:** …
        **Критерии приёмки:**
        - …

        В самом конце на отдельной строке выведи маркер: <<FINAL>>
        Во время интервью не выдавай ТЗ частями — только вопросы.
    """.trimIndent()
                )
            )
        )

        // Пополняем историю
        if (history.isEmpty()) {
            history += system
        }
        history += ResponseMessage(role = "user", content = listOf(MessageContent(text = message)))

        // Сколько ассистент уже задал вопросов?
        val asked = history.count { it.role == "assistant" && it.content.any { c -> c.text.isNotBlank() } }

        // Принудительный «надзорник»: если лимит достигнут, подталкиваем к финалу
        val supervisor = if (asked >= MAX_QUESTIONS) {
            ResponseMessage(
                role = "assistant",
                content = listOf(
                    MessageContent(
                        type = "output_text",
                        text = "Пожалуйста, сформируй итоговое ТЗ сейчас. Заверши ответ маркером $FINAL_MARKER"
                    )
                )
            )
        } else null

        // Собираем окно контекста (обрезка истории при росте)
        val window = history.takeLast(24).toMutableList().apply {
            if (supervisor != null) add(supervisor)
        }

        // Запрашиваем модель
        val reply = openAiClient.generateReply(window)

        // Сохраняем ассистентский ответ в историю (assistant должен говорить output_text)
        history += ResponseMessage(
            role = "assistant",
            content = listOf(
                MessageContent(
                    type = "output_text",
                    text = reply
                )
            )
        )


        // Проверяем финал
        val isFinal = reply.contains(FINAL_MARKER)
        val cleanReply = if (isFinal) reply.substringBefore(FINAL_MARKER).trim() else reply

        if (isFinal) {
            // Завершаем сессию
            SESSIONS.remove(sessionId)
        } else {
            // Ограничиваем размер истории в памяти (защита от разрастания)
            if (history.size > 60) {
                val preservedSystem = history.first()
                SESSIONS[sessionId] =
                    (mutableListOf(preservedSystem) + history.takeLast(40)).toMutableList()
            }
        }

        logger.info("Final reply (sid={}): {}", sessionId, cleanReply)
        call.respond(ChatResponse(reply = cleanReply))
    }
}
