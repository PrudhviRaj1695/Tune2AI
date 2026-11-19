package com.tune2ai.sdk.docs

import com.tune2ai.sdk.Tune2AI
import com.tune2ai.sdk.api.RequestId
import com.tune2ai.sdk.api.Tune2AIEvent
import com.tune2ai.sdk.core.workflow.StepType
import com.tune2ai.sdk.core.workflow.Transition
import com.tune2ai.sdk.core.workflow.WorkflowDefinition
import com.tune2ai.sdk.core.workflow.WorkflowStep

val greetWorkflow = WorkflowDefinition(
    id = "greet",
    version = 1,
    inputs = listOf("name"),
    steps = listOf(
        WorkflowStep(
            id = "ask",
            type = StepType.MODEL,
            prompt = "Greet {name} in one sentence.",
            output = "greeting",
            transitions = listOf(Transition("shout")),
        ),
        WorkflowStep(
            id = "shout",
            type = StepType.TOOL,
            tool = "upper",
            arguments = """{"text":"{greeting}"}""",
        ),
    ),
)

suspend fun runGreeting(ai: Tune2AI): String {
    val problems = ai.workflow.validate(greetWorkflow)
    check(problems.isEmpty()) { "Invalid workflow: $problems" }
    var result = ""
    ai.workflow.start(greetWorkflow, inputs = mapOf("name" to "Ada")).collect { event ->
        if (event is Tune2AIEvent.TextDelta) result += event.text
    }
    return result
}

suspend fun resumeUnfinished(ai: Tune2AI): List<String> {
    val resumed = mutableListOf<String>()
    for (run in ai.workflow.list().filter { it.canResume && it.definitionId == greetWorkflow.id }) {
        ai.workflow.resume(greetWorkflow, RequestId(run.runId)).collect { }
        resumed += run.runId
    }
    return resumed
}

suspend fun deleteFinishedRuns(ai: Tune2AI) {
    ai.workflow.list().filter { !it.canResume }.forEach { ai.workflow.delete(it.runId) }
}
