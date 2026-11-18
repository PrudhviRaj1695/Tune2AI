package com.tune2ai.sdk.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune2ai.sdk.InferenceBackendContract
import com.tune2ai.sdk.inference.InferenceBackend
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaBackendContractTest : InferenceBackendContract() {
    override fun backend(): InferenceBackend = LlamaBackend()
    override fun validModel() = TestModel.descriptor()
    override fun brokenModel() = TestModel.broken()
}
