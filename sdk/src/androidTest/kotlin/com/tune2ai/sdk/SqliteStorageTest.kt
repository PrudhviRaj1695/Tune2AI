package com.tune2ai.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tune2ai.sdk.core.Storage
import com.tune2ai.sdk.platform.android.SqliteStorage
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteStorageTest : StorageContract() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "storage-test-${System.nanoTime()}.db"
    private val opened = mutableListOf<SqliteStorage>()

    override fun newStorage(): Storage = SqliteStorage(context, name).also { opened += it }

    override fun reopen(): Storage {
        opened.forEach { it.close() }
        return newStorage()
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }
}
