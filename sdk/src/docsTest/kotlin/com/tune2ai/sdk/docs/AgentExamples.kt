package com.tune2ai.sdk.docs

import com.tune2ai.sdk.Tune2AI
import com.tune2ai.sdk.api.AgentLimits
import com.tune2ai.sdk.api.ErrorCategory
import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun addTools(builder: Tune2AI.Builder): Tune2AI.Builder = builder.addTool(
    Tool(
        name = "add",
        description = "Adds two integers.",
        parametersSchema = """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}""",
    ) { args ->
        val numbers = Json.parseToJsonElement(args).jsonObject
        val sum = numbers.getValue("a").jsonPrimitive.int + numbers.getValue("b").jsonPrimitive.int
        """{"sum":$sum}"""
    },
)

suspend fun askAgent(ai: Tune2AI, log: (String) -> Unit): String {
    val answer = StringBuilder()
    ai.agent.run(
        task = "What is 2 + 3?",
        tools = setOf("add"),
        limits = AgentLimits(maxSteps = 4, maxToolCalls = 2),
    ).collect { event ->
        when (event) {
            is Tune2AIEvent.ToolCall -> log("calling ${event.name} with ${event.argumentsJson}")
            is Tune2AIEvent.ToolResult -> log("${event.name} returned ${event.resultJson}")
            is Tune2AIEvent.TextDelta -> answer.append(event.text)
            is Tune2AIEvent.Failed ->
                if (event.error.category == ErrorCategory.LIMIT_REACHED) log("the agent ran out of budget") else throw event.error
            else -> Unit
        }
    }
    return answer.toString()
}
