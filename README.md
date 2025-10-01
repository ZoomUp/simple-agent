# Kotlin Ktor Tool-Calling Agent

Этот проект реализует минимальный backend-агента на Kotlin и Ktor, который использует OpenAI Responses API и поддерживает вызов инструментов (tool calling).

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
