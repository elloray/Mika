package com.wangzan101.mika.task

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.McpTaskTools
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.mcp.McpServerState
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class RemoteTaskRecoveryDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MikaApp>()

    @Test fun versionOneReceiptsUpgradeWithoutLosingResultsOrSingleUseFileRequests() {
        val name = "receipts-migration-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val item = com.wangzan101.mika.chat.SharedFile("result.csv", 3, "Downloads/Mika", "content://exports/7")
        val task = RemoteTask("files", "mcp-files", "original goal", now, null)
        try {
            app.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("""CREATE TABLE receipts (id TEXT PRIMARY KEY NOT NULL, conversation TEXT, goal TEXT,
                    started INTEGER NOT NULL, new_chat INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, result TEXT, steers TEXT)""")
                db.execSQL("INSERT INTO receipts(id,conversation,goal,started,new_chat,result) VALUES(?,?,?,?,?,?)",
                    arrayOf("legacy", "mcp-old", "old goal", now, 1, """{"type":"cancelled"}"""))
                db.version = 1
            }
            RemoteTaskReceipts(app, name).use { store ->
                assertEquals(TaskState.Cancelled, store.find("legacy", owner = "one")!!.state)
                assertNull(store.find("legacy", owner = "two"))
                store.insert(task.copy(owner = "one"), true, fileRequest = "original files")
                store.finish(task.id, TaskState.Cancelled, emptyList(), runId = "saved-run")
                store.saveFiles(task.id, listOf(item))
            }
            RemoteTaskReceipts(app, name).use { store ->
                assertEquals(listOf(item.remoteFile()), store.find(task.id, "original goal", null, "original files")!!.files)
                assertEquals("saved-run", store.runId(task.id, "one"))
                assertEquals(task.id, store.taskIdForRun("saved-run", "one"))
                assertNull(store.runId(task.id, "two"))
                assertEquals(listOf(item), store.files(task.id))
                val conflict = runCatching { store.find(task.id, "imported path") }.exceptionOrNull() as RemoteTaskError
                assertEquals(RemoteTaskError.Code.CONFLICT, conflict.code)
                store.forget(task.conversation)
                assertTrue(store.files(task.id).isEmpty())
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun rowIdPaginationIsStableForEqualTimesAndNewInserts() {
        val db = "receipts-pages-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        fun task(id: String, owner: String = "one") = RemoteTask(id, "mcp-$id", "goal $id", now, null, owner = owner)
        try {
            RemoteTaskReceipts(app, db).use { store ->
                for (id in listOf("a", "b", "c", "d", "e")) store.insert(task(id), false)
                val first = store.page(2, null, "one")
                assertEquals(listOf("e", "d"), first.tasks.map { it.id })
                assertNotNull(first.nextBefore)
                store.insert(task("new"), false)
                val second = store.page(2, first.nextBefore, "one")
                assertEquals(listOf("c", "b"), second.tasks.map { it.id })
                val third = store.page(2, second.nextBefore, "one")
                assertEquals(listOf("a"), third.tasks.map { it.id })
                assertNull(third.nextBefore)
                assertTrue(store.page(20, null, "two").tasks.isEmpty())
            }
        } finally { app.deleteDatabase(db) }
    }

    @Test fun retainedIdsAndTerminalResultsSurviveReopening() {
        val db = "receipts-test-" + UUID.randomUUID()
        val done = RemoteTask("first", "mcp-first", "review", System.currentTimeMillis(), null)
        val result = TaskState.Succeeded("answer", false, "unchecked items", unconfirmed = listOf("one"))
        val notes = listOf(RemoteSteer("s1", "joined"))
        try {
            RemoteTaskReceipts(app, db).use { store ->
                store.insert(done, true)
                assertTrue(store.claimStart(done.id))
                assertFalse(store.claimStart(done.id))
                store.finish(done.id, result, notes, runId = "saved-run")
                store.insert(done.copy(id = "cancelled"), false)
                store.finish("cancelled", TaskState.Cancelled, emptyList())
                repeat(105) { store.insert(done.copy(id = "queued-$it", conversation = "another"), false) }
            }
            RemoteTaskReceipts(app, db).use { store ->
                assertEquals(done.copy(state = result, steers = notes, runId = "saved-run"), store.find(done.id))
                assertEquals(TaskState.Cancelled, store.find("cancelled")!!.state)
                assertEquals(FailKind.Interrupted, (store.find("queued-0")!!.state as TaskState.Failed).kind)
                assertFalse(store.claimStart("queued-0"))
                assertEquals("queued-104", store.recent(1).single().id)
                store.forget(done.conversation)
                assertFalse(store.finish(done.id, result, notes))
                val gone = runCatching { store.find(done.id, done.goal) }.exceptionOrNull() as RemoteTaskError
                assertEquals(RemoteTaskError.Code.GONE, gone.code)
                assertTrue(store.recent(110).none { it.conversation == done.conversation })
            }
        } finally { app.deleteDatabase(db) }
    }

    // 收尾的和删对话留下的占位按条数、按时间清;没收尾的不清,清掉的编号再来就当新请求
    @Test fun finishedReceiptsAndPlaceholdersArePrunedUnfinishedKept() {
        val db = "receipts-test-" + UUID.randomUUID()
        var now = 10 * RemoteTaskReceipts.KEEP_MS
        fun task(id: String, chat: String = "mcp-$id") = RemoteTask(id, chat, "goal $id", now, null)
        try {
            RemoteTaskReceipts(app, db, clock = { now }, keep = 3).use { store ->
                store.insert(task("running"), false)
                store.insert(task("gone"), false)
                store.forget("mcp-gone")
                for (id in listOf("a", "b")) { store.insert(task(id), false); store.finish(id, TaskState.Cancelled, emptyList()) }
                fun gone() = (runCatching { store.find("gone") }.exceptionOrNull() as? RemoteTaskError)?.code
                assertNotNull(store.find("running")); assertEquals(RemoteTaskError.Code.GONE, gone())
                // 第五条收件:最早的两条落在最近三条之外,收尾的占位被清,还没收尾的留着
                store.insert(task("c"), false)
                assertNotNull(store.find("running"))
                assertNull(gone()); assertNull(store.find("gone"))
                assertNotNull(store.find("a"))
                store.finish("c", TaskState.Cancelled, emptyList())
            }
            now += RemoteTaskReceipts.KEEP_MS + 1
            RemoteTaskReceipts(app, db, clock = { now }, keep = 3).use { store ->
                // 重开:running 标成中断后和 a b c 一起过了保留时间,都清掉
                assertEquals(emptyList<RemoteTask>(), store.recent(10))
                store.insert(task("a"), false)
                assertTrue(store.claimStart("a"))
            }
        } finally { app.deleteDatabase(db) }
    }

    /** An external CLI driver can submit the first request and kill this process during the second run. */
    @Test fun concurrentRetriesStartOnlyOneTurn() {
        val driver = InstrumentationRegistry.getArguments().getString("recoveryDriver")
        val id = driver ?: UUID.randomUUID().toString()
        val goal = "Recovery check $id"
        val service = RemoteTaskService(app.remoteTasks)
        val model = ScriptedModel().apply { intent = "ACT" }
        val pool = Executors.newFixedThreadPool(6)
        val usb = app.mcp.usb.enabled.value
        var chat: String? = null
        try {
            testTasks.modelFactory = { _, _ -> model }
            testTasks.lightModelFactory = { _, _ -> null }
            testTasks.visionModelFactory = { _, _ -> null }
            if (driver != null) {
                ui.runOnIdle { app.mcp.setUsbEnabled(true) }
                ui.waitUntil(30_000) { app.mcp.state.value is McpServerState.Listening }
                File(app.cacheDir, "recovery-listening-$id").writeText("ready")
                ui.waitUntil(90_000) { app.remoteTasks.get(id) != null }
            }
            val tasks = pool.invokeAll(List(12) { Callable { service.start(goal, null, id) } }).map { it.get() }
            val conversation = tasks.first().conversation
            chat = conversation
            assertTrue(tasks.all { it.id == id && it.conversation == conversation })
            val mcp = runBlocking { McpTaskTools(service).call(McpTaskTools.RUN, buildJsonObject {
                put("goal", goal); put("request_id", id)
            }) }
            assertEquals(id, mcp.getValue("task_id").jsonPrimitive.content)
            for ((otherGoal, thread) in listOf("other" to null, goal to conversation)) {
                val error = runCatching { service.start(otherGoal, thread, id) }.exceptionOrNull() as RemoteTaskError
                assertEquals(RemoteTaskError.Code.CONFLICT, error.code)
            }
            model.awaitCall()
            assertEquals(1, app.taskState.runs.value.count { it.conversationId == conversation })
            if (driver == null) {
                // Make exactly this receipt write fail until the ordinary phone turn has been saved.
                app.openOrCreateDatabase("remote-tasks.db", 0, null).use { db ->
                    db.execSQL("""CREATE TRIGGER reject_test_result BEFORE UPDATE OF result ON receipts
                        WHEN NEW.id='$id' BEGIN SELECT RAISE(ABORT,'test write failure'); END""")
                    try {
                        model.answer("Recovered answer")
                        ui.waitUntil(30_000) { app.conversations.turns(conversation).any { it.userText == goal } }
                        val failure = runCatching { service.get(id) }.exceptionOrNull() as RemoteTaskError
                        assertEquals(RemoteTaskError.Code.UNAVAILABLE, failure.code)
                    } finally { db.execSQL("DROP TRIGGER reject_test_result") }
                }
            } else model.answer("Recovered answer")
            ui.waitUntil(30_000) { service.get(id).state is TaskState.Succeeded }
            assertEquals("Recovered answer", (service.start(goal, null, id).state as TaskState.Succeeded).answer)
            ui.waitUntil(20_000) { app.conversations.turns(conversation).count { it.userText == goal } == 1 }
            if (driver != null) {
                val interrupted = requireNotNull(InstrumentationRegistry.getArguments().getString("interruptId"))
                service.start("Interrupt check $interrupted", conversation, interrupted)
                model.awaitCall()
                File(app.cacheDir, "recovery-ready-$id").writeText("ready")
                // The disposable emulator driver force-stops this process here.
                ui.waitUntil(90_000) { false }
            }
            runBlocking { app.conversationDeleter.delete(conversation) }
            chat = null
            val gone = runCatching { service.start(goal, null, id) }.exceptionOrNull() as RemoteTaskError
            assertEquals(RemoteTaskError.Code.GONE, gone.code)
        } finally {
            pool.shutdownNow()
            testTasks.modelFactory = TaskEnvironment.defaultModelFactory
            testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
            testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            if (driver != null && !usb) ui.runOnIdle { app.mcp.setUsbEnabled(false) }
            chat?.let { runBlocking { app.conversationDeleter.delete(it) } }
        }
    }
}
