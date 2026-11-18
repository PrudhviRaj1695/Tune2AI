package com.tune2ai.sdk.core

import com.tune2ai.sdk.api.ModelDescriptor
import com.tune2ai.sdk.api.ModelState
import com.tune2ai.sdk.api.RequestOptions
import com.tune2ai.sdk.api.Task
import com.tune2ai.sdk.api.Tune2AIException.NoCompatibleRoute
import com.tune2ai.sdk.inference.InferenceBackend
import com.tune2ai.sdk.services.model.ModelManager

data class Route(val model: ModelDescriptor, val backend: InferenceBackend)

/**
 * Picks a model and backend for a request (FR-ORC-2). Keeps no model state: availability and
 * compatibility come from the [ModelManager].
 *
 * Order: explicit preference → an already loaded model → the smallest model that fits.
 */
class Router(private val models: ModelManager) {

    fun route(task: Task, options: RequestOptions): Route {
        options.backend?.let { id ->
            val backend = models.backend(id) ?: throw NoCompatibleRoute("Backend '$id' is not registered")
            backend.unavailableReason?.let { throw NoCompatibleRoute("Backend '$id' is unavailable: $it") }
        }
        options.model?.let { return preferred(it, task, options.backend) }

        val candidates = models.candidates(task).filter { options.backend == null || it.backend.id == options.backend }
        val best = candidates.minWithOrNull(compareBy({ !it.loaded }, { it.model.memoryBytes }))
            ?: throw NoCompatibleRoute(noRouteMessage(task, options.backend))
        return Route(best.model, best.backend)
    }

    private fun preferred(id: String, task: Task, backendId: String?): Route {
        val model = models.find(id) ?: throw NoCompatibleRoute("Model '$id' is not registered")
        if (task !in model.tasks) throw NoCompatibleRoute("Model '$id' does not support $task")
        when (val state = models.state(id)) {
            is ModelState.Unavailable -> throw NoCompatibleRoute("Model '$id' is unavailable: ${state.reason}")
            is ModelState.Failed -> throw NoCompatibleRoute("Model '$id' failed to load: ${state.reason}")
            else -> Unit
        }
        val backend = models.backendsFor(model, task).firstOrNull { backendId == null || it.id == backendId }
            ?: throw NoCompatibleRoute(
                if (backendId == null) "No backend runs $task for '$id' (${model.format})"
                else "Backend '$backendId' cannot run '$id' (${model.format}) for $task",
            )
        return Route(model, backend)
    }

    private fun noRouteMessage(task: Task, backendId: String?): String {
        val reasons = models.explainUnavailable(task)
        val scope = if (backendId == null) "" else " on backend '$backendId'"
        return if (reasons.isEmpty()) "No models are registered" else "No model can run $task$scope. " + reasons.joinToString("; ")
    }
}
