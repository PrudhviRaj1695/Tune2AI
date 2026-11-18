package com.tune2ai.sdk.api

/**
 * Stable error categories. Their names are part of the public API (NFR-11): they cross the
 * Studio JavaScript bridge and appear in logs, so never rename one; add new ones instead.
 *
 * Cancellation is not an error: it ends a stream with [Tune2AIEvent.Cancelled].
 */
enum class ErrorCategory {
    INVALID_REQUEST,
    NO_COMPATIBLE_ROUTE,
    MODEL_UNAVAILABLE,
    RESOURCE_LIMIT,
    BACKEND_FAILURE,
    TOOL_FAILURE,
    STORAGE_FAILURE,
    TIMEOUT,
    LIMIT_REACHED,
}

/** Every failure the SDK reports. Match on the subclass or on [category]. */
sealed class Tune2AIException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val category: ErrorCategory

    class InvalidRequest(message: String) : Tune2AIException(message) {
        override val category get() = ErrorCategory.INVALID_REQUEST
    }

    /** No registered model/backend satisfies the request; [message] names the unmet capability. */
    class NoCompatibleRoute(message: String) : Tune2AIException(message) {
        override val category get() = ErrorCategory.NO_COMPATIBLE_ROUTE
    }

    class ModelUnavailable(message: String, cause: Throwable? = null) : Tune2AIException(message, cause) {
        override val category get() = ErrorCategory.MODEL_UNAVAILABLE
    }

    /** Device memory, thermal, or battery limits prevented or stopped the work. */
    class ResourceLimit(message: String) : Tune2AIException(message) {
        override val category get() = ErrorCategory.RESOURCE_LIMIT
    }

    class BackendFailure(message: String, cause: Throwable? = null) : Tune2AIException(message, cause) {
        override val category get() = ErrorCategory.BACKEND_FAILURE
    }

    class ToolFailure(message: String, cause: Throwable? = null) : Tune2AIException(message, cause) {
        override val category get() = ErrorCategory.TOOL_FAILURE
    }

    class StorageFailure(message: String, cause: Throwable? = null) : Tune2AIException(message, cause) {
        override val category get() = ErrorCategory.STORAGE_FAILURE
    }

    class Timeout(message: String) : Tune2AIException(message) {
        override val category get() = ErrorCategory.TIMEOUT
    }

    /** An agent or workflow used its step or tool-call budget before finishing. */
    class LimitReached(message: String) : Tune2AIException(message) {
        override val category get() = ErrorCategory.LIMIT_REACHED
    }
}
