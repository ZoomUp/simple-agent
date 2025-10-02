# Kotlin Ktor Agent — День 2: Настройка AI (Structured Outputs)

Простой чат (клиент → Ktor-бэкенд → OpenAI Responses API), настроенный так, чтобы модель **всегда** возвращала один и тот же структурированный JSON:

```json
{
  "answer": "string",
  "topic": "string",
  "confidence": 0.0,
  "suggest": ["string", "..."]
}
```

* В `system`-подсказке зафиксировано требование отвечать **только** этим объектом.
* При вызове Responses API используется `text.format` с `json_schema` (строгая схема).
* Бэкенд парсит JSON и логирует статус: `OK parsed` или `fallback`.
* UI показывает `answer`, `topic`, `confidence` и `suggest[]` (кнопками).

## Требования

* Kotlin 1.9+
* Gradle (Kotlin DSL)
* OpenAI API key с правами на Responses API

## Переменные окружения

```bash
export OPENAI_API_KEY="ваш_api_ключ"
# опционально: модель
export OPENAI_MODEL="gpt-4.1-nano"
# опционально: порт
export PORT=8080
```

## Сборка и запуск

```bash
./gradlew run
```

Сервер поднимется на `http://localhost:8080`.

## Пример запроса

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Привет"}'
```

### Пример ожидаемого ответа

```json
{
  "answer": "Привет! Чем могу помочь?",
  "topic": "general",
  "confidence": 1.0,
  "suggest": ["Расскажи о погоде", "Помоги с переводом", "Что нового в технологиях"]
}
```

> Если модель вернёт не-JSON, бэкенд сделает fallback и вернёт:
>
> ```json
> {
>   "answer": "<сырой текст>",
>   "topic": "general",
>   "confidence": 0.5,
>   "suggest": []
> }
> ```
