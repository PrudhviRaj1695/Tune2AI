package com.tune2ai.sdk

import com.tune2ai.sdk.inference.InferenceBackend

class FakeBackendContractTest : InferenceBackendContract() {
    override fun backend(): InferenceBackend = FakeBackend(tokens = List(2_000) { " tok" }, tokenDelayMillis = 2)
    override fun validModel() = fakeModel()
    override fun brokenModel() = fakeModel(path = "/models/bad.broken")
}
