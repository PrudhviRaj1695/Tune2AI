package com.tune2ai.sdk.core.workflow

import kotlinx.serialization.Serializable

/**
 * A repeatable multi-step task (FR-ORC-5): ordered [steps], the named [inputs] a run must supply, and
 * the transitions each step carries. Execution starts at the first step and follows transitions until
 * a step with none is reached, whose output is the run's terminal result. `@Serializable` is the JSON
 * form persisted and resumed in T2AI-036.
 *
 * Variables flow between steps through a string map seeded with [inputs]. Prompts and tool arguments
 * interpolate `{name}` placeholders from it; each step stores its result under [WorkflowStep.output].
 */
@Serializable
data class WorkflowDefinition(
    val id: String,
    val version: Int = 1,
    val inputs: List<String> = emptyList(),
    val steps: List<WorkflowStep>,
)

@Serializable
enum class StepType { MODEL, TOOL }

/**
 * One step. A [MODEL][StepType.MODEL] step renders [prompt] and sends it through the controller; a
 * [TOOL][StepType.TOOL] step renders [arguments] and invokes [tool]. The result is stored under
 * [output] (defaulting to [id]). [transitions] are tried in order; the first whose condition holds
 * decides the next step. No transition matching (or none declared) ends the run at this step.
 */
@Serializable
data class WorkflowStep(
    val id: String,
    val type: StepType,
    val prompt: String? = null,
    val tool: String? = null,
    val arguments: String? = null,
    val output: String? = null,
    val transitions: List<Transition> = emptyList(),
) {
    val outputVar: String get() = output ?: id
}

/**
 * An edge to step [to]. Unconditional when [whenVar] is null; otherwise taken only when the variable
 * [whenVar]'s current value equals [equals]. Order transitions most-specific first, unconditional last
 * (the "else" branch).
 */
@Serializable
data class Transition(
    val to: String,
    val whenVar: String? = null,
    val equals: String? = null,
)

/**
 * A run's persisted state (FR-ORC-6), written after each step to the `workflows` storage namespace and
 * read back by [WorkflowEngine.resume]. Holds enough to continue — the next step to run and the current
 * variables — but not the definition itself: the caller re-supplies that on resume and [definitionVersion]
 * is checked against it so a changed definition is refused. [nextStepId] is null once the run completed.
 */
@Serializable
data class WorkflowCheckpoint(
    val runId: String,
    val definitionId: String,
    val definitionVersion: Int,
    val nextStepId: String?,
    val variables: Map<String, String>,
    val outputTokens: Int = 0,
) {
    val done: Boolean get() = nextStepId == null
}
