package com.tune2ai.sdk

import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.core.tools.JsonSchema
import com.tune2ai.sdk.core.tools.ParsedStep
import com.tune2ai.sdk.core.tools.ToolCallingEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    private val addSchema = """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"},"mode":{"type":"string","enum":["sum","diff"]}},"required":["a","b"]}"""
    private val add = Tool("add", "Adds two integers.", addSchema) { args ->
        val o = Json.parseToJsonElement(args).jsonObject
        """{"sum":${o["a"].toString().toInt() + o["b"].toString().toInt()}}"""
    }
    private val slow = Tool("slow", "Never finishes in time.", timeoutMillis = 100) { Thread.sleep(10_000); "late" }
    private val broken = Tool("broken", "Always throws.") { error("disk on fire") }
    private val plain = Tool("plain", "Returns plain text.") { "just text" }
    private val engine = ToolCallingEngine(listOf(add, slow, broken, plain))
    private val all = engine.names

    @Test
    fun schemaSubsetValidation() {
        val schema = Json.parseToJsonElement(addSchema).jsonObject
        fun errors(json: String) = JsonSchema.validate(schema, Json.parseToJsonElement(json))
        assertTrue(errors("""{"a":1,"b":2}""").isEmpty())
        assertEquals(listOf("$.b is required"), errors("""{"a":1}"""))
        assertEquals(listOf("$.a must be an integer"), errors("""{"a":1.5,"b":2}"""))
        assertEquals(listOf("$.a must be an integer"), errors("""{"a":"1","b":2}"""))
        assertTrue(errors("""{"a":1,"b":2,"mode":"avg"}""").single().contains("must be one of"))
        assertEquals(listOf("$ must be an object"), errors("[1,2]"))
    }

    @Test
    fun registrationRejectsBadNamesAndSchemas() {
        assertThrows(IllegalArgumentException::class.java) { ToolCallingEngine(listOf(Tool("Bad Name", "x") { "" })) }
        assertThrows(IllegalArgumentException::class.java) { ToolCallingEngine(listOf(Tool("t", "x", """{"type":"array"}""") { "" })) }
        assertThrows(IllegalArgumentException::class.java) { ToolCallingEngine(listOf(Tool("t", "x", """{"type":"object","properties":{"x":{"type":"date"}}}""") { "" })) }
        assertThrows(IllegalArgumentException::class.java) { ToolCallingEngine(listOf(add, add)) }
    }

    @Test
    fun parsesBothToolCallStylesAndPlainAnswers() {
        assertEquals(ParsedStep.Call("add", """{"a":2,"b":3}"""), ToolCallingEngine.parse("""{"tool": "add", "arguments": {"a": 2, "b": 3}}"""))
        assertEquals(ParsedStep.Call("add", """{"a":2,"b":3}"""), ToolCallingEngine.parse("<tool_call>\n{\"name\": \"add\", \"arguments\": {\"a\": 2, \"b\": 3}}\n</tool_call>"))
        assertEquals(ParsedStep.Call("plain", "{}"), ToolCallingEngine.parse("""{"tool":"plain"}"""))
        assertEquals(ParsedStep.Answer, ToolCallingEngine.parse("The answer is 5."))
        assertEquals(ParsedStep.Answer, ToolCallingEngine.parse("Use {curly} braces in Kotlin templates."))
        assertTrue(ToolCallingEngine.parse("""{"tool": "add", "arguments": {"a": 2""") is ParsedStep.Malformed)
        assertTrue(ToolCallingEngine.parse("""{"arguments": {}}""") is ParsedStep.Malformed)
    }

    @Test
    fun invokesValidCallsAndTurnsEveryFailureIntoAnErrorResult() = runBlocking {
        assertEquals("""{"sum":5}""", engine.invoke("add", """{"a":2,"b":3}""", all).resultJson)
        assertEquals("\"just text\"", engine.invoke("plain", "{}", all).resultJson)

        fun assertError(outcome: com.tune2ai.sdk.core.tools.ToolOutcome, fragment: String) {
            assertTrue(outcome.isError)
            assertTrue(outcome.resultJson, outcome.resultJson.contains(fragment))
        }
        assertError(engine.invoke("add", """{"a":2}""", all), "b is required")
        assertError(engine.invoke("add", "not json", all), "must be a JSON object")
        assertError(engine.invoke("add", """{"a":2,"b":3}""", setOf("plain")), "not allowed")
        assertError(engine.invoke("nope", "{}", all + "nope"), "Unknown tool")
        assertError(engine.invoke("broken", "{}", all), "disk on fire")
        val started = System.nanoTime()
        assertError(engine.invoke("slow", "{}", all), "timed out after 100 ms")
        assertTrue("timeout interrupts the handler", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun instructionsListOnlyAllowedTools() {
        val text = engine.instructions(setOf("add"))
        assertTrue(text.contains("- add: Adds two integers."))
        assertFalse(text.contains("slow"))
        assertTrue(text.contains(""""tool": "<tool name>""""))
    }
}
