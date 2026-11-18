package com.tune2ai.sdk

import com.tune2ai.sdk.core.InMemoryStorage
import com.tune2ai.sdk.core.Storage

class InMemoryStorageTest : StorageContract() {
    override fun newStorage(): Storage = InMemoryStorage()
}
