package com.tune2ai.sdk.core.workflow

/** `{name}` placeholders used in prompts and tool arguments. */
private val PLACEHOLDER = Regex("""\{([a-zA-Z_][a-zA-Z0-9_]*)\}""")

internal fun placeholders(text: String?): Set<String> =
    if (text == null) emptySet() else PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

/** Substitutes `{name}` with [vars]. Callers validate references first, so a miss is a programming error. */
internal fun render(text: String, vars: Map<String, String>): String =
    PLACEHOLDER.replace(text) { vars[it.groupValues[1]] ?: error("unbound variable {${it.groupValues[1]}}") }

/**
 * Checks a [WorkflowDefinition] before any step runs (FR-ORC-5). Returns every problem found; an empty
 * list means the definition is safe to execute. Catches unknown transition targets, unreachable steps,
 * and cycles (which this engine cannot bound), plus structural and variable-reference errors that would
 * otherwise fail mid-run.
 */
object WorkflowValidator {
    fun check(definition: WorkflowDefinition, knownTools: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        val steps = definition.steps
        if (steps.isEmpty()) return listOf("A workflow needs at least one step")

        val ids = steps.map { it.id }
        ids.filter { it.isBlank() }.forEach { _ -> problems += "Step ids must not be blank" }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += "Duplicate step id '$it'" }
        val byId = steps.associateBy { it.id }

        // Variables a step may reference: declared inputs plus every step's output.
        val knownVars = definition.inputs.toSet() + steps.map { it.outputVar }

        steps.forEach { step ->
            when (step.type) {
                StepType.MODEL -> {
                    if (step.prompt.isNullOrBlank()) problems += "Model step '${step.id}' needs a prompt"
                    if (step.tool != null || step.arguments != null) problems += "Model step '${step.id}' must not set tool/arguments"
                }
                StepType.TOOL -> {
                    when {
                        step.tool.isNullOrBlank() -> problems += "Tool step '${step.id}' needs a tool name"
                        step.tool !in knownTools -> problems += "Tool step '${step.id}' uses unregistered tool '${step.tool}'"
                    }
                    if (step.prompt != null) problems += "Tool step '${step.id}' must not set a prompt"
                }
            }
            (placeholders(step.prompt) + placeholders(step.arguments)).minus(knownVars).forEach {
                problems += "Step '${step.id}' references unknown variable '{$it}'"
            }
            step.transitions.forEach { t ->
                if (t.to !in byId) problems += "Step '${step.id}' transitions to unknown step '${t.to}'"
                if (t.whenVar != null && t.whenVar !in knownVars) problems += "Step '${step.id}' branches on unknown variable '${t.whenVar}'"
            }
        }

        reachabilityAndCycles(steps, byId, problems)
        return problems
    }

    private fun reachabilityAndCycles(
        steps: List<WorkflowStep>,
        byId: Map<String, WorkflowStep>,
        problems: MutableList<String>,
    ) {
        val start = steps.first().id
        val reachable = mutableSetOf<String>()
        fun walk(id: String) {
            if (!reachable.add(id)) return
            byId[id]?.transitions?.forEach { if (it.to in byId) walk(it.to) }
        }
        walk(start)
        steps.map { it.id }.filterNot { it in reachable }.forEach { problems += "Step '$it' is unreachable" }

        // Cycle detection over the reachable graph (DFS with a recursion stack). Any back-edge is an
        // unbounded loop: there is no loop-count construct in a definition.
        val visited = mutableSetOf<String>()
        val onStack = mutableSetOf<String>()
        fun dfs(id: String): Boolean {
            visited += id
            onStack += id
            byId[id]?.transitions?.forEach { t ->
                if (t.to !in byId) return@forEach
                if (t.to in onStack) {
                    problems += "Workflow has a cycle through step '${t.to}'"
                    return true
                }
                if (t.to !in visited && dfs(t.to)) return true
            }
            onStack -= id
            return false
        }
        dfs(start)
    }
}
