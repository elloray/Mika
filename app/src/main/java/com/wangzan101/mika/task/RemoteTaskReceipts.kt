package com.wangzan101.mika.task

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteOwnership
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteTaskPage
import kotlinx.serialization.json.Json

/** 唯一一版远程收件迁移:v1 一次升级到同时包含附件、运行编号和令牌归属的 v2。 */
internal class RemoteTaskReceipts(
    context: Context, name: String = "remote-tasks.db",
    private val clock: () -> Long = System::currentTimeMillis, private val keep: Int = KEEP,
) : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE receipts (
            id TEXT PRIMARY KEY NOT NULL, conversation TEXT, goal TEXT, started INTEGER NOT NULL,
            new_chat INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, result TEXT, steers TEXT,
            file_request TEXT, files TEXT, run_id TEXT, owner TEXT)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == 1 && newVersion == 2) { "Unknown remote task receipt schema $oldVersion -> $newVersion" }
        // 这是一次有序迁移。不要在不同功能里各自声明互不兼容的 v2。
        db.execSQL("ALTER TABLE receipts ADD COLUMN file_request TEXT")
        db.execSQL("ALTER TABLE receipts ADD COLUMN files TEXT")
        db.execSQL("ALTER TABLE receipts ADD COLUMN run_id TEXT")
        db.execSQL("ALTER TABLE receipts ADD COLUMN owner TEXT")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun decodeFiles(text: String?) = text?.let {
        runCatching { json.decodeFromString<List<SharedFile>>(it) }.getOrNull()
    }.orEmpty()

    private data class Entry(val task: RemoteTask, val newChat: Boolean, val fileRequest: String?, val rowId: Long)
    private val columns = "id, conversation, goal, started, result, steers, new_chat, run_id, files, owner, rowid, file_request"

    private fun Cursor.entry(): Entry {
        if (isNull(2)) throw RemoteTaskError.General(RemoteTaskError.Code.GONE, "This task's conversation was deleted.")
        val result = getString(4)?.let(RemoteTaskRecord::decode)
        val task = RemoteTask(
            id = getString(0), conversation = getString(1), goal = getString(2), createdAtMs = getLong(3),
            state = result?.state, steers = getString(5)?.let(RemoteTaskRecord::decodeSteers).orEmpty(),
            runId = getString(7) ?: result?.runId,
            files = decodeFiles(getString(8)).map { it.remoteFile() }, owner = getString(9),
        )
        return Entry(task, getInt(6) != 0, getString(11), getLong(10))
    }

    init {
        val interrupted: TaskState.Terminal = TaskState.Failed("The phone process stopped before this task finished.", FailKind.Interrupted)
        writableDatabase.execSQL("UPDATE receipts SET result=? WHERE result IS NULL AND goal IS NOT NULL",
            arrayOf(RemoteTaskRecord.encode(interrupted)))
        prune(clock())
    }

    private fun prune(now: Long) {
        writableDatabase.execSQL("""DELETE FROM receipts WHERE (result IS NOT NULL OR goal IS NULL) AND
            (started < ? OR rowid <= (SELECT rowid FROM receipts ORDER BY rowid DESC LIMIT 1 OFFSET ?))""",
            arrayOf<Any>(now - KEEP_MS, keep))
    }

    /** v1 没有令牌概念。首个读取旧任务的已授权电脑认领它;此后换令牌不能读取或导出。 */
    private fun claimLegacy(owner: String?) {
        if (owner != null) writableDatabase.update("receipts", ContentValues().apply { put("owner", owner) },
            "owner IS NULL", null)
    }

    fun migrateUsbOwner(previousOwner: String) {
        writableDatabase.update("receipts", ContentValues().apply { put("owner", RemoteOwnership.USB) },
            "owner=?", arrayOf(previousOwner))
    }

    fun find(id: String, goal: String? = null, requestedChat: String? = null, fileRequest: String? = null,
        owner: String? = null): RemoteTask? {
        claimLegacy(owner)
        val where = if (owner == null) "id=?" else "id=? AND owner=?"
        val args = if (owner == null) arrayOf(id) else arrayOf(id, owner)
        return readableDatabase.rawQuery("SELECT $columns FROM receipts WHERE $where", args).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val entry = cursor.entry()
            val task = entry.task
            val originalChat = if (entry.newChat) null else task.conversation
            if ((goal != null && goal != task.goal) ||
                ((goal != null || fileRequest != null) && requestedChat != originalChat) ||
                (fileRequest != null && fileRequest != entry.fileRequest)) {
                throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, "This request_id was already used for a different task.")
            }
            task
        }
    }

    /** 附件任务落库的目标已附加文件路径;重试应按原始目标、会话和附件组成的请求签名核验。 */
    fun findFileRequest(id: String, requestedChat: String?, fileRequest: String, owner: String?): RemoteTask? =
        find(id, goal = null, requestedChat, fileRequest, owner)

    fun recent(limit: Int, owner: String? = null): List<RemoteTask> {
        claimLegacy(owner)
        val where = if (owner == null) "goal IS NOT NULL" else "goal IS NOT NULL AND owner=?"
        val args = if (owner == null) arrayOf(limit.toString()) else arrayOf(owner, limit.toString())
        return readableDatabase.rawQuery("SELECT $columns FROM receipts WHERE $where ORDER BY rowid DESC LIMIT ?", args)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.entry().task) } }
    }

    /** rowid 游标只向旧记录移动;同时间戳和翻页期间插入的新任务都不会让记录重复或漏掉。 */
    fun page(limit: Int, before: String?, owner: String): RemoteTaskPage {
        claimLegacy(owner)
        val cursor = before?.toLongOrNull()?.takeIf { it > 0 }
        if (before != null && cursor == null)
            throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, "Invalid pagination cursor")
        val rows = if (cursor == null) readableDatabase.rawQuery(
            "SELECT $columns FROM receipts WHERE goal IS NOT NULL AND owner=? ORDER BY rowid DESC LIMIT ?",
            arrayOf(owner, (limit + 1).toString()))
        else readableDatabase.rawQuery(
            "SELECT $columns FROM receipts WHERE goal IS NOT NULL AND owner=? AND rowid<? ORDER BY rowid DESC LIMIT ?",
            arrayOf(owner, cursor.toString(), (limit + 1).toString()))
        val entries = rows.use { buildList { while (it.moveToNext()) add(it.entry()) } }
        val page = entries.take(limit)
        return RemoteTaskPage(page.map { it.task }, if (entries.size > limit) page.last().rowId.toString() else null)
    }

    fun conversationAvailable(conversation: String, owner: String): Boolean {
        claimLegacy(owner)
        return readableDatabase.rawQuery("SELECT 1 FROM receipts WHERE conversation=? AND goal IS NOT NULL AND owner<>? LIMIT 1",
            arrayOf(conversation, owner)).use { !it.moveToFirst() }
    }

    fun insert(task: RemoteTask, newChat: Boolean, fileRequest: String? = null) {
        writableDatabase.insertOrThrow("receipts", null, ContentValues().apply {
            put("id", task.id); put("conversation", task.conversation); put("goal", task.goal)
            put("started", task.createdAtMs); put("new_chat", newChat); put("file_request", fileRequest)
            put("owner", task.owner)
        })
        prune(clock())
    }

    fun saveFiles(id: String, files: List<SharedFile>) {
        writableDatabase.update("receipts", ContentValues().apply { put("files", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SharedFile.serializer()), files)) },
            "id=? AND goal IS NOT NULL", arrayOf(id))
    }

    fun files(id: String): List<SharedFile> = readableDatabase.rawQuery(
        "SELECT files FROM receipts WHERE id=? AND goal IS NOT NULL", arrayOf(id)
    ).use { if (it.moveToFirst()) decodeFiles(it.getString(0)) else emptyList() }

    fun claimStart(id: String): Boolean = writableDatabase.update("receipts", ContentValues().apply { put("claimed", 1) },
        "id=? AND goal IS NOT NULL AND result IS NULL AND claimed=0", arrayOf(id)) == 1

    fun finish(id: String, state: TaskState.Terminal, steers: List<RemoteSteer>, onlyUnclaimed: Boolean = false, runId: String? = null): Boolean {
        val condition = "id=? AND goal IS NOT NULL AND result IS NULL" + if (onlyUnclaimed) " AND claimed=0" else ""
        return writableDatabase.update("receipts", ContentValues().apply {
            val end = if (state is TaskState.Succeeded) state.copy(sources = emptyList()) else state
            put("result", RemoteTaskRecord.encode(end, runId))
            if (runId != null) put("run_id", runId)
            if (steers.isNotEmpty()) put("steers", RemoteTaskRecord.encodeSteers(steers))
        }, condition, arrayOf(id)) == 1
    }

    fun forget(conversation: String) {
        writableDatabase.execSQL("""UPDATE receipts SET conversation=NULL, goal=NULL, result=NULL, steers=NULL,
            claimed=0, new_chat=0, run_id=NULL, files=NULL, file_request=NULL WHERE conversation=?""", arrayOf(conversation))
    }

    fun runId(taskId: String, owner: String? = null): String? {
        claimLegacy(owner)
        val where = if (owner == null) "id=? AND goal IS NOT NULL" else "id=? AND goal IS NOT NULL AND owner=?"
        val args = if (owner == null) arrayOf(taskId) else arrayOf(taskId, owner)
        return readableDatabase.rawQuery("SELECT run_id FROM receipts WHERE $where", args)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
    }

    fun taskIdForRun(runId: String, owner: String? = null): String? {
        claimLegacy(owner)
        val where = if (owner == null) "run_id=? AND goal IS NOT NULL" else "run_id=? AND goal IS NOT NULL AND owner=?"
        val args = if (owner == null) arrayOf(runId) else arrayOf(runId, owner)
        return readableDatabase.rawQuery("SELECT id FROM receipts WHERE $where", args)
            .use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun rememberRun(taskId: String, runId: String) {
        writableDatabase.update("receipts", ContentValues().apply { put("run_id", runId) },
            "id=? AND goal IS NOT NULL", arrayOf(taskId))
    }

    companion object {
        const val KEEP_MS = 30L * 24 * 60 * 60_000
        const val KEEP = 1_000
    }
}
