package com.tune2ai.sdk.core.tools

import com.tune2ai.sdk.api.Tool
import com.tune2ai.sdk.api.Tune2AIException.InvalidRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The outcome of one tool call. Failures are results too ([isError]), never request failures. */
internal data class ToolOutcome(val resultJson: String, val isError: Boolean)

/** What a model step asked for. */
internal sealed interface ParsedStep {
    data object Answer : ParsedStep
    data class Call(val name: String, val argumentsJson: String) : ParsedStep
    data class Malformed(val reason: String) : ParsedStep
}

/**
 * Registers host-provided tools and invokes them safely (FR-ORC-3): allow-list check, schema
 * validation, timeout, and error capture. Tool arguments and results are never logged (NFR-2).
 */
internal class ToolCallingEngine(
    tools: List<Tool>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val tools: Map<String, Pair<Tool, JsonObject>>

    init {
        val byName = LinkedHashMap<String, Pair<Tool, JsonObject>>()
        tools.forEach { tool ->
            require(NAME.matches(tool.name)) { "Tool name '${tool.name}' must match ${NAME.pattern}" }
            require(tool.name !in byName) { "Tool '${tool.name}' is registered twice" }
            require(tool.timeoutMillis > 0) { "Tool '${tool.name}' needs a positive timeout" }
            val schema = runCatching { Json.parseToJsonElement(tool.parametersSchema) }.getOrNull()
            require(schema is JsonObject && (schema["type"] as? JsonPrimitive)?.content == "object") {
                "Tool '${tool.name}' parametersSchema must be a JSON object schema with \"type\": \"object\""
            }
            val problems = JsonSchema.check(schema)
            require(problems.isEmpty()) { "Tool '${tool.name}' schema: ${problems.joinToString("; ")}" }
            byName[tool.name] = tool to schema
        }
        this.tools = byName
    }

    val names: Set<String> get() = tools.keys

    /** Fails before running anything if [requested] names a tool that is not registered. */
    fun checkAllowed(requested: Set<String>) {
        val unknown = requested - tools.keys
        if (unknown.isNotEmpty()) throw InvalidRequest("Unknown tools: ${unknown.sorted()}")
    }

    /** System prompt section describing [allowed] tools and how to call them. */
    fun instructions(allowed: Set<String>): String = buildString {
        appendLine("You can call tools to complete the task. Available tools:")
        allowed.mapNotNull { tools[it] }.forEach { (tool, schema) ->
            appendLine("- ${tool.name}: ${tool.description} Arguments (JSON schema): ${schema["properties"] ?: "{}"}")
        }
        appendLine()
        appendLine("To call a tool, reply with only this JSON and nothing else:")
        appendLine("""{"tool": "<tool name>", "arguments": {<arguments>}}""")
        append("You will then receive the tool result. Call another tool if you need more information; otherwise answer the user in plain text. Never invent tool results.")
    }

    suspend fun invoke(name: String, argumentsJson: String, allowed: Set<String>): ToolOutcome {
        if (name !in allowed) return error("Tool '$name' is not allowed for this request")
        val (tool, schema) = tools[name] ?: return error("Unknown tool '$name'")
        val args = runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) }.getOrNull() as? JsonObject
            ?: return error("Arguments must be a JSON object")
        val problems = JsonSchema.validate(schema, args)
        if (problems.isNotEmpty()) return error("Invalid arguments: ${problems.joinToString("; ")}")
        val result = try {
            withTimeout(tool.timeoutMillis) { runInterruptible(dispatcher) { tool.handler.call(args.toString()) } }
        } catch (e: TimeoutCancellationException) {
            return error("Tool '$name' timed out after ${tool.timeoutMillis} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return error("Tool '$name' failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val asJson = runCatching { Json.parseToJsonElement(result) }.getOrNull()?.toString() ?: JsonPrimitive(result).toString()
        return ToolOutcome(asJson, isError = false)
    }

    private fun error(message: String) = ToolOutcome(buildJsonObject { put("error", message) }.toString(), isError = true)

    companion object {
        private val NAME = Regex("[a-z][a-z0-9_]{0,63}")
        private val TAGGED = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)

        /**
         * Reads a model step: a tool call as bare JSON `{"tool": …, "arguments": {…}}` (or `"name"`
         * instead of `"tool"`), or wrapped in `<tool_call>…</tool_call>` as Qwen-style models emit;
         * anything else is a plain answer.
         */
        fun parse(text: String): ParsedStep {
            val trimmed = text.trim()
            val looksLikeCall = trimmed.startsWith("{") || trimmed.startsWith("<tool_call>")
            val candidate = TAGGED.find(trimmed)?.groupValues?.get(1)?.trim()
                ?: trimmed.indexOf('{').takeIf { it >= 0 }?.let { start ->
                    trimmed.lastIndexOf('}').takeIf { it > start }?.let { trimmed.substring(start, it + 1) }
                }
                ?: return if (looksLikeCall) ParsedStep.Malformed("incomplete JSON") else ParsedStep.Answer
            val json = runCatching { Json.parseToJsonElement(candidate).jsonObject }.getOrNull()
            if (json == null) return if (looksLikeCall) ParsedStep.Malformed("not valid JSON") else ParsedStep.Answer
            val name = ((json["tool"] ?: json["name"]) as? JsonPrimitive)?.contentOrNull
                ?: return if (looksLikeCall) ParsedStep.Malformed("missing \"tool\"") else ParsedStep.Answer
            val args = json["arguments"] ?: JsonObject(emptyMap())
            if (args !is JsonObject) return ParsedStep.Malformed("\"arguments\" must be an object")
            return ParsedStep.Call(name, args.toString())
        }
    }
}
