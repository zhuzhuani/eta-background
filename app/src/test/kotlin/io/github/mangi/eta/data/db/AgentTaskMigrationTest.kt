package io.github.mangi.eta.data.db

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class AgentTaskMigrationTest {
    @Test fun migration23To24AddsOnlyAutomationTablesAndPreservesAuthAndUserRows() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(23) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE model_providers (id TEXT PRIMARY KEY NOT NULL, auth_mode TEXT NOT NULL DEFAULT 'api_key')")
                    db.execSQL("INSERT INTO model_providers(id,auth_mode) VALUES('oauth','codex_oauth')")
                    db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY NOT NULL, body TEXT NOT NULL)")
                    db.execSQL("INSERT INTO conversations(id,body) VALUES('chat','keep me')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            EtaDatabase.MIGRATION_23_24.migrate(db)
            db.query("SELECT auth_mode FROM model_providers WHERE id='oauth'").use { assertTrue(it.moveToFirst()); assertEquals("codex_oauth",it.getString(0)) }
            db.query("SELECT body FROM conversations WHERE id='chat'").use { assertTrue(it.moveToFirst()); assertEquals("keep me",it.getString(0)) }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('agent_tasks','agent_task_runs')").use { assertEquals(2,it.count) }
            db.query("PRAGMA index_list('agent_task_runs')").use { cursor ->
                var unique = false
                while(cursor.moveToNext()) if(cursor.getString(1)=="index_agent_task_runs_taskId_fireKey") unique=cursor.getInt(2)==1
                assertTrue(unique)
            }
            assertEquals(22, EtaDatabase.MIGRATION_22_23.startVersion)
            assertEquals(23, EtaDatabase.MIGRATION_22_23.endVersion)
        }
    }
}
