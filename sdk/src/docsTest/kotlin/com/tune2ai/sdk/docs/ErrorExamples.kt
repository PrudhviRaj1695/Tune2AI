package com.tune2ai.sdk.docs

import com.tune2ai.sdk.api.Tune2AIException

fun advice(error: Tune2AIException): String = when (error) {
    is Tune2AIException.InvalidRequest -> "Fix the request: ${error.message}"
    is Tune2AIException.NoCompatibleRoute -> "No model can run this: ${error.message}"
    is Tune2AIException.ModelUnavailable -> "Model problem: ${error.message}"
    is Tune2AIException.ResourceLimit -> "Wait for the device to cool down or free memory: ${error.message}"
    is Tune2AIException.BackendFailure -> "The backend failed: ${error.message}"
    is Tune2AIException.ToolFailure -> "A tool failed: ${error.message}"
    is Tune2AIException.StorageFailure -> "Storage failed: ${error.message}"
    is Tune2AIException.Timeout -> "The request took longer than its deadline"
    is Tune2AIException.LimitReached -> "The agent or workflow used its budget: ${error.message}"
}
