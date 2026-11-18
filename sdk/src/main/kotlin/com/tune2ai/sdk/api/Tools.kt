package com.tune2ai.sdk.api

/**
 * Runs a tool. Receives the arguments as a JSON object string (already validated against the tool's
 * schema) and returns the result as JSON (plain text is wrapped as a JSON string). Runs on a
 * background thread and may block; it is interrupted when its timeout passes or the request is
 * cancelled. Throwing reports an error result to the model instead of failing the request.
 */
fun interface ToolHandler {
    fun call(argumentsJson: String): String
}

/**
 * A capability the host app offers to agents (decision D6: host-provided tools only).
 *
 * @param name lowercase identifier the model uses to call the tool, for example `device_status`.
 * @param description one sentence the model reads to decide when to call it.
 * @param parametersSchema JSON schema for the arguments; the root must be an object. Supported
 *   keywords: `type` (object, string, number, integer, boolean, array), `properties`, `required`,
 *   `items`, `enum`, `description`.
 */
class Tool @JvmOverloads constructor(
    val name: String,
    val description: String,
    val parametersSchema: String = """{"type":"object","properties":{}}""",
    val timeoutMillis: Long = 10_000,
    val handler: ToolHandler,
)

/** Budget for one agent run (FR-ORC-4). Reaching it ends the run with `LIMIT_REACHED`. */
data class AgentLimits @JvmOverloads constructor(
    val maxSteps: Int = 6,
    val maxToolCalls: Int = 5,
)
