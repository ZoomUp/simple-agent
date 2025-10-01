package com.example.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolArgsSerializationTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `echo args serialization round trip`() {
        val args = EchoArgs(text = "Привет, агент!")
        val encoded = json.encodeToString(args)
        val decoded = json.decodeFromString<EchoArgs>(encoded)
        assertEquals(args, decoded)
    }
}
