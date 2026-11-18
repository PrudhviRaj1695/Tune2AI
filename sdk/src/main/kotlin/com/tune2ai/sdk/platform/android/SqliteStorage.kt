package com.tune2ai.sdk.platform.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tune2ai.sdk.core.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [Storage] in an app-private SQLite database (decision D5). One table, keyed by (namespace, key). */
internal class SqliteStorage(
    context: Context,
    name: String = "tune2ai.db",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Storage, AutoCloseable {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE records (namespace TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL, PRIMARY KEY (namespace, key)) WITHOUT ROWID",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private suspend fun <T> db(block: () -> T): T = withContext(io) { platformCall(block) }

    override suspend fun get(namespace: String, key: String): String? = db {
        helper.readableDatabase
            .rawQuery("SELECT value FROM records WHERE namespace = ? AND key = ?", arrayOf(namespace, key))
            .use { if (it.moveToFirst()) it.getString(0) else null }
    }

    override suspend fun put(namespace: String, key: String, value: String) = db {
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO records (namespace, key, value, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(namespace, key, value, System.currentTimeMillis()),
        )
    }

    override suspend fun delete(namespace: String, key: String): Boolean = db {
        helper.writableDatabase.delete("records", "namespace = ? AND key = ?", arrayOf(namespace, key)) > 0
    }

    override suspend fun list(namespace: String): Map<String, String> = db {
        helper.readableDatabase
            .rawQuery("SELECT key, value FROM records WHERE namespace = ? ORDER BY key", arrayOf(namespace))
            .use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
    }

    override suspend fun clear(namespace: String) = db {
        helper.writableDatabase.delete("records", "namespace = ?", arrayOf(namespace))
        Unit
    }

    override fun close() = helper.close()
}
