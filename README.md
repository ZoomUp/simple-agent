# Kotlin Ktor Tool-Calling Agent

Этот проект реализует минимальный backend-агента на Kotlin и Ktor, который использует OpenAI Responses API и поддерживает вызов инструментов (tool calling).

## День 2 — Настройка AI (structured outputs)

Интерфейс и сервер доработаны так, чтобы ответы модели всегда представляли собой JSON-объект с полями `answer` и `source`.

- В системное сообщение добавлена жёсткая инструкция: «Отвечай строго объектом JSON с полями answer и source. Ничего вне JSON не добавляй».
- При обращении к Responses API используется Structured Outputs (`text.format`) с блоком `{ type: "json_schema", name: "answer_with_source", schema: {...}, strict: true }` и `text.verbosity = "medium"`.
- Бэкенд пытается распарсить JSON-ответ и логирует результат (`OK parsed` или `fallback`). Если модель вернула неожиданный текст, сервер всё равно отвечает объектом `{ answer: "…", source: "model" }`.
- UI показывает поле `answer` как основной текст и подпись `Источник: …` под ним.
- В логах хранится сырой ответ модели (усечённый при необходимости).

## Требования
- Kotlin 1.9+
- Gradle (Kotlin DSL)
- OpenAI API key с правами на использование Responses API

## Переменные окружения
Перед запуском экспортируйте ключ API:

```bash
export OPENAI_API_KEY="ваш_api_ключ"
# опционально, можно переопределить модель
export OPENAI_MODEL="gpt-4.1-mini"
# опционально, можно задать порт
export PORT=8080
```

## Сборка и запуск

```bash
./gradlew run
```

Сервер запустится на `http://localhost:8080` (если не переопределён переменной `PORT`).

## Пример запроса

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Сколько времени?"}'
```

Ожидается, что модель вызовет инструмент `get_time`, сервер выполнит его и вернёт время в ISO-формате.

Повторение текста:

```bash
curl -s -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Повтори: Привет, агент!"}'
```
