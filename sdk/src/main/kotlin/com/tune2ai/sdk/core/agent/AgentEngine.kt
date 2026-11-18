package com.tune2ai.sdk.core.agent

import com.tune2ai.sdk.api.AgentLimits
import com.tune2ai.sdk.api.ChatMessage
import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.api.Tune2AIEvent.Progress
import com.tune2ai.sdk.api.Tune2AIEvent.Stage
import com.tune2ai.sdk.api.Tune2AIException
import com.tune2ai.sdk.api.Usage
import com.tune2ai.sdk.core.RequestController
import com.tune2ai.sdk.core.tools.ParsedStep
import com.tune2ai.sdk.core.tools.ToolCallingEngine
import com.tune2ai.sdk.core.withTerminalGuarantee
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/**
 * Runs a bounded model ↔ tool loop (FR-ORC-4). Every model step goes through the
 * [RequestController], so routing, context assembly, and limits apply to each step. Tool calls go
 * through the [ToolCallingEngine]. Ends with exactly one terminal event: completed, cancelled,
 * limit reached ([Tune2AIException.LimitReached]), or failed.
 */
class AgentEngine(
    private val controller: RequestController,
    private val tools: ToolCallingEngine,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun run(
        task: String,
        allowedTools: Set<String>? = null,
        limits: AgentLimits = AgentLimits(),
        options: RequestOptions = RequestOptions(),
        requestId: RequestId = RequestId.random(),
    ): Flow<Tune2AIEvent> = flow {
        val start = clock()
        emit(Progress(requestId, Stage.RECEIVED))
        if (task.isBlank()) throw Tune2AIException.InvalidRequest("An agent task must not be blank")
        if (limits.maxSteps < 1 || limits.maxToolCalls < 0) throw Tune2AIException.InvalidRequest("Agent limits must be positive")
        val allowed = allowedTools ?: tools.names
        tools.checkAllowed(allowed)
        emit(Progress(requestId, Stage.VALIDATED, "${allowed.size} tools · up to ${limits.maxSteps} steps"))

        val messages = mutableListOf(ChatMessage.system(tools.instructions(allowed)), ChatMessage.user(task))
        var toolCalls = 0
        var outputTokens = 0
        var firstToken: Long? = null

        withTimeout(options.deadlineMillis) {
            for (step in 1..limits.maxSteps) {
                val text = StringBuilder()
                var streaming: Boolean? = null // decided at the first visible character
                controller.chat(messages, options, requestId).collect { event ->
                    when (event) {
                        is Progress -> if (event.stage !in setOf(Stage.RECEIVED, Stage.VALIDATED)) {
                            emit(event.copy(detail = listOfNotNull("Step $step", event.detail).joinToString(" · ")))
                        }
                        is Tune2AIEvent.TextDelta -> {
                            text.append(event.text)
                            if (streaming == null && text.isNotBlank()) {
                                // Hold back steps that start like a tool call ({ or <tool_call>); stream plain answers live.
                                val first = text.trimStart().first()
                                streaming = first != '{' && first != '<'
                                if (streaming == true) {
                                    if (firstToken == null) firstToken = clock() - start
                                    emit(Tune2AIEvent.TextDelta(requestId, text.toString()))
                                }
                            } else if (streaming == true) {
                                emit(event)
                            }
                        }
                        is Tune2AIEvent.Completed -> outputTokens += event.usage.outputTokens
                        is Tune2AIEvent.Failed -> throw event.error
                        is Tune2AIEvent.Cancelled -> throw CancellationException("Model step cancelled")
                        else -> Unit
                    }
                }

                when (val parsed = ToolCallingEngine.parse(text.toString())) {
                    is ParsedStep.Answer -> {
                        if (streaming != true) {
                            firstToken = clock() - start
                            emit(Tune2AIEvent.TextDelta(requestId, text.toString().trim()))
                        }
                        emit(Tune2AIEvent.Completed(requestId, Usage(outputTokens, firstToken, clock() - start)))
                        return@withTimeout
                    }
                    is ParsedStep.Malformed -> {
                        messages += ChatMessage.assistant(text.toString())
                        messages += ChatMessage.user(
                            "That tool call could not be read (${parsed.reason}). Reply with only " +
                                """{"tool": "<name>", "arguments": {...}} or answer in plain text.""",
                        )
                    }
                    is ParsedStep.Call -> {
                        if (toolCalls >= limits.maxToolCalls) {
                            throw Tune2AIException.LimitReached("The agent reached its limit of ${limits.maxToolCalls} tool calls")
                        }
                        toolCalls++
                        val callId = "call_$toolCalls"
                        emit(Tune2AIEvent.ToolCall(requestId, callId, parsed.name, parsed.argumentsJson))
                        val outcome = tools.invoke(parsed.name, parsed.argumentsJson, allowed)
                        emit(Tune2AIEvent.ToolResult(requestId, callId, parsed.name, outcome.resultJson, outcome.isError))
                        messages += ChatMessage.assistant(text.toString().trim())
                        messages += ChatMessage.user("Tool result for ${parsed.name}: ${outcome.resultJson}")
                    }
                }
            }
            throw Tune2AIException.LimitReached("The agent used all ${limits.maxSteps} steps without a final answer")
        }
    }.withTerminalGuarantee(requestId)
}
