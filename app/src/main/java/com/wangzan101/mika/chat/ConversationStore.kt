package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.memory.NoteKind
import com.wangzan101.mika.core.skills.SkillNote
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 每个对话一个小 JSON 文件,放在 App 私有目录;重启后仍在。 */
class ConversationStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readConversation: (File) -> String = { it.readText() },
) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun newConversation(): String = UUID.randomUUID().toString()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * 写过的对话编号:存进一轮、改了某一轮、去掉记忆提示时各发一次,不管是谁写的(对话页、悬浮胶囊、定时任务)。
     * 对话页和侧栏据此重读,屏上的就是存下的那份。
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _deletions = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 删掉的对话编号:[delete] 时发一次([changes] 也照发)。悬浮胶囊据此拿掉这个对话刚结束的任务、卡片不再接着它。 */
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    /**
     * 用户此刻在哪个对话,见 [CurrentChat]。存在对话目录里的 `current` 文件(不是 `.json`,不进 [list]);
     * 进程重启后照样读得回来,胶囊不必等对话文件写下来才知道新开的是哪个。
     */
    private val _current = MutableStateFlow(readCurrent())

    val current: StateFlow<CurrentChat?> = _current.asStateFlow()

    fun currentChat(): CurrentChat? = _current.value

    /** 记下用户此刻在哪个对话;删掉的对话记不住。写进去了发一次 [current]。 */
    @Synchronized
    fun setCurrent(chat: CurrentChat?) {
        val target = when (chat) {
            is CurrentChat.Open -> if (safe(chat.id) in deleted) null else chat
            else -> chat
        }
        if (target == _current.value) return
        dir.mkdirs()
        runCatching {
            when (target) {
                null -> currentFile().delete()
                CurrentChat.Fresh -> currentFile().writeText(CURRENT_FRESH)
                is CurrentChat.Open -> currentFile().writeText(safe(target.id))
            }
        }
        _current.value = target
    }

    private fun readCurrent(): CurrentChat? {
        val f = currentFile().takeIf { it.isFile } ?: return null
        val text = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        return when {
            text.isEmpty() -> null
            text == CURRENT_FRESH -> CurrentChat.Fresh
            else -> safe(text).takeIf(String::isNotEmpty)?.let(CurrentChat::Open)
        }
    }

    /**
     * 对话列表,最近改过的在前;[ConversationSummary.title] 是标题(用户改的或起好的),没有时是第一句话;
     * [ConversationSummary.pinnedAt] 是置顶的时间;[ConversationSummary.page] 是从哪一页开始的;
     * [ConversationSummary.branchOf] 是从哪个对话的第几轮分出来的。没有内容的对话不列。
     */
    fun list(): List<ConversationSummary> = listPage(0, Int.MAX_VALUE)

    /**
     * 侧栏搜索用的正文索引:标题由调用方连同 [ConversationSummary] 一起传进来,正文按对话文件的修改时间缓存。
     * 读文件不占本类的锁,不挡正在收尾的任务补写。
     */
    val search: ConversationSearch = ConversationSearch(::file) { read(it)?.map { record -> record.first } }

    /** 尚未保存首轮的活动对话排在前面，与磁盘上的对话共用一套分页。 */
    @Synchronized
    fun listPage(offset: Int, limit: Int, pending: List<ConversationSummary> = emptyList()): List<ConversationSummary> {
        val unsaved = pending.asSequence().filter { firstMessage(file(it.id)) == null }
        val saved = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .map { it to it.lastModified() }
            .sortedWith(compareByDescending<Pair<File, Long>> { it.second }.thenBy { it.first.name })
            .asSequence().mapNotNull { (f, updatedAt) ->
                val first = firstMessage(f) ?: return@mapNotNull null
                val id = f.nameWithoutExtension
                ConversationSummary(id, title(id) ?: first, updatedAt, pinnedAt(id), pageOrigin(id), branchOrigin(id))
            }
        return (unsaved + saved).drop(offset).take(limit).toList()
    }

    private data class FirstMessage(val modified: Long, val size: Long, val text: String?)
    private val firstMessages = mutableMapOf<String, FirstMessage>()

    private fun firstMessage(f: File): String? {
        if (!f.isFile) return null
        val modified = f.lastModified()
        val size = f.length()
        firstMessages[f.name]?.let { if (it.modified == modified && it.size == size) return it.text }
        val text = read(f)?.firstOrNull()?.first?.userText
        firstMessages[f.name] = FirstMessage(modified, size, text)
        return text
    }

    /** 对话起好的标题(见 [ConversationTitler]);没有、读不到为 null。标题放在对话文件旁边的 `.title` 文件里,不动对话文件的格式。 */
    @Synchronized
    fun title(conversationId: String): String? =
        runCatching { titleFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * 删掉过的对话。删之后才到的「补写」(后台起好的标题、任务收尾后补的结局和交代)不再把它写回来;
     * 再往里存进一轮([append])就是重新开始用这个编号,从这里拿掉。只记在进程里。
     */
    private val deleted = mutableSetOf<String>()

    /**
     * 删掉一个对话存下的一切:对话文件、标题、置顶、网页来源、分支来源,以及写到一半的临时文件和挪到一边的损坏文件。
     * 删了什么都发一次 [changes] 和 [deletions]。
     * 对话的工作区、定时任务、在跑的任务不归这里管,见 [ConversationDeleter]。
     */
    @Synchronized
    fun delete(conversationId: String) {
        deleted += safe(conversationId)
        val main = file(conversationId).name
        firstMessages.remove(main)
        dir.listFiles { f ->
            f.isFile && (f.name == main || f.name.startsWith("$main.") || f.name == titleFile(conversationId).name || f.name == pinFile(conversationId).name ||
                f.name == pageFile(conversationId).name || f.name == branchFile(conversationId).name)
        }
            .orEmpty().forEach { it.delete() }
        // 删掉的正是用户此刻在的那个:不再指着它,胶囊回落到最近那个
        if (_current.value == CurrentChat.Open(safe(conversationId))) setCurrent(null)
        _changes.tryEmit(conversationId)
        _deletions.tryEmit(conversationId)
    }

    /**
     * 给对话记下起好的标题;已经有标题(起好的或用户改的)就不动(标题只起一次)。写进去了返回 true。
     * 所以后台起标题晚到时,用户先改的名字不会被盖掉。
     */
    @Synchronized
    fun setTitle(conversationId: String, title: String): Boolean {
        if (title(conversationId) != null) return false
        return writeTitle(conversationId, title)
    }

    /** 用户给对话改名:不管有没有标题都换成 [title];空的不改。写进去了返回 true。 */
    @Synchronized
    fun rename(conversationId: String, title: String): Boolean = writeTitle(conversationId, title)

    private fun writeTitle(conversationId: String, title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || safe(conversationId) in deleted) return false
        dir.mkdirs()
        val ok = runCatching { titleFile(conversationId).writeText(t) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** 对话置顶的时间;没置顶、读不到为 null。放在对话文件旁边的 `.pin` 文件里。 */
    @Synchronized
    fun pinnedAt(conversationId: String): Long? =
        runCatching { pinFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** 置顶([pinned] 为真,记下时间 [at])或取消置顶。删掉的对话不动。改了返回 true。 */
    @Synchronized
    fun setPinned(conversationId: String, pinned: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = pinFile(conversationId)
        val ok = if (pinned) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else !f.exists() || f.delete()
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 对话是从哪一页开始的(用户在浏览器页上对着一页问 Mika);不是、读不到为 null。
     * 放在对话文件旁边的 `.page` 文件里(JSON:url、host、title),不动对话文件的格式;旧对话没有这个文件。
     */
    @Synchronized
    fun pageOrigin(conversationId: String): PageOrigin? = runCatching {
        val f = pageFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        PageOrigin(str(o, "url") ?: return@runCatching null, str(o, "host") ?: return@runCatching null, str(o, "title").orEmpty())
    }.getOrNull()

    /** 记下对话从哪一页开始(之后在同一页上接着问时换成最新的那一页)。删掉的对话不动。写进去了返回 true。 */
    @Synchronized
    fun setPageOrigin(conversationId: String, origin: PageOrigin): Boolean {
        if (safe(conversationId) in deleted) return false
        dir.mkdirs()
        val json = buildJsonObject { put("url", origin.url); put("host", origin.host); put("title", origin.title) }
        val ok = runCatching { pageFile(conversationId).writeText(json.toString()) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 把 [sourceId] 的轮次复制成新对话 [targetId],复制到 [throughTurn](0 起,含在内)为止。
     * 源不存在、源文件损坏、[throughTurn] 越界、目标已有对话文件时返回 false,什么都不写。
     * [title] 非空白时写成新对话标题;[origin] 非空时写 `<targetId>.branch`。
     * 标题、网页来源、分支来源都是旁文件,写不进去不影响对话本身已复制好。
     */
    @Synchronized
    fun branch(
        sourceId: String,
        targetId: String,
        throughTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, throughTurn, includeTurn = true, title, origin)

    /**
     * 把 [sourceId] 在 [beforeTurn] 之前的轮次(不含这一轮)复制成新对话 [targetId],给「从用户消息重新问」用。
     * [beforeTurn] 为 0 时新对话还没有轮次(和刚新开的对话一样,不进侧栏列表),第一句话存进来后才算这个对话的第一轮。
     * 其余条件同 [branch]。
     */
    @Synchronized
    fun branchBefore(
        sourceId: String,
        targetId: String,
        beforeTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, beforeTurn, includeTurn = false, title, origin)

    /** [branch] 和 [branchBefore] 共用:复制 [sourceId] 的前 [turn] 轮([includeTurn] 时连这一轮),再写标题、网页来源、分支来源。 */
    private fun copyBranch(sourceId: String, targetId: String, turn: Int, includeTurn: Boolean, title: String?, origin: BranchOrigin?): Boolean {
        val source = file(sourceId)
        if (!source.isFile) return false
        val records = read(source) ?: return false
        if (turn !in records.indices) return false
        if (file(targetId).exists()) return false
        deleted -= safe(targetId)
        write(targetId, records.take(if (includeTurn) turn + 1 else turn))
        title?.let { writeTitle(targetId, it) }
        pageOrigin(sourceId)?.let { setPageOrigin(targetId, it) }
        origin?.let { writeBranchOrigin(targetId, it) }
        return true
    }

    /** 分支来源:从哪个对话的第几轮分出来的;没有、读不到为 null。放在 `<id>.branch` 里。 */
    @Synchronized
    fun branchOrigin(conversationId: String): BranchOrigin? = runCatching {
        val f = branchFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        val parentId = str(o, "parent")?.takeIf(String::isNotEmpty) ?: return@runCatching null
        val parentTurn = (o["turn"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        val afterTurn = (o["after"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return@runCatching null
        val at = (o["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@runCatching null
        BranchOrigin(parentId, parentTurn, afterTurn, at)
    }.getOrNull()

    /** 写分支来源;先写临时文件再改名,写到一半被杀不会留下半个文件。 */
    private fun writeBranchOrigin(conversationId: String, origin: BranchOrigin) {
        dir.mkdirs()
        val json = buildJsonObject {
            put("parent", origin.parentId); put("turn", origin.parentTurn); put("after", origin.afterTurn); put("at", origin.at)
        }
        val f = branchFile(conversationId)
        val tmp = File(dir, "${f.name}.tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    /** 文件损坏时当作空对话,不让它挡住新任务。 */
    @Synchronized
    fun turns(conversationId: String): List<Turn> = records(conversationId).map { it.first }

    /** 每一轮连同这一轮导出的文件(文件卡片)。 */
    @Synchronized
    fun records(conversationId: String): List<Pair<Turn, List<SharedFile>>> {
        val f = file(conversationId)
        if (!f.isFile) return emptyList()
        return read(f).orEmpty()
    }

    internal data class RecordsSnapshot(
        val conversations: List<Pair<String, List<Pair<Turn, List<SharedFile>>>>>,
        val hasReadFailures: Boolean,
    )

    /** 读取所有已保存的对话；单个文件读不到时保留其余结果并标记失败。 */
    @Synchronized
    internal fun allRecords(): RecordsSnapshot {
        val exists = runCatching { dir.exists() }.getOrElse { return RecordsSnapshot(emptyList(), true) }
        if (!exists) return RecordsSnapshot(emptyList(), false)
        val files = runCatching { dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } }.getOrNull()
            ?: return RecordsSnapshot(emptyList(), true)
        var failed = false
        val conversations = files.sortedBy { it.name }.mapNotNull { f ->
            val records = runCatching { read(f) }.getOrNull()
            if (records == null) {
                failed = true
                null
            } else f.nameWithoutExtension to records
        }
        return RecordsSnapshot(conversations, failed)
    }

    /** 读不懂(不是 JSON 数组)时返回 null。 */
    private fun read(f: File): List<Pair<Turn, List<SharedFile>>>? = try {
        (Json.parseToJsonElement(readConversation(f)) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val turn = Turn(
                field("userText") ?: return@mapNotNull null, field("outcome").orEmpty(), field("status") ?: return@mapNotNull null, field("note"), field("runId"),
                memoryNotes = objects(o, "memoryNotes").mapNotNull { n ->
                    val kind = NoteKind.entries.find { it.name == str(n, "kind") } ?: return@mapNotNull null
                    MemoryNote(kind, str(n, "memoryId") ?: return@mapNotNull null, str(n, "undoToken"), str(n, "pendingId"))
                },
                usedMemory = (o["usedMemory"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                skillNotes = objects(o, "skillNotes").map { n -> SkillNote(str(n, "text").orEmpty(), str(n, "draftId"), str(n, "suspended")) },
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                result = field("result"),
                steers = (o["steers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                interruptedReplies = (o["interruptedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                finishedReplies = (o["finishedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                keptPage = field("keptPage"),
                keptPageClosed = field("keptPageClosed"),
                atMs = field("atMs")?.toLongOrNull(),
                verified = field("verified")?.toBooleanStrictOrNull(),
                remoteTaskId = field("remoteTaskId"),
            )
            turn to (o["files"] as? JsonArray).orEmpty().mapNotNull(::fileOf)
        }
    } catch (e: SerializationException) {
        null
    }

    private fun fileOf(e: kotlinx.serialization.json.JsonElement): SharedFile? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return SharedFile(field("name") ?: return null, field("size")?.toLongOrNull() ?: 0, field("savedAs").orEmpty(), field("uri").orEmpty(), field("sha256"))
    }

    @Synchronized
    fun append(conversationId: String, turn: Turn, files: List<SharedFile> = emptyList()) {
        deleted -= safe(conversationId)
        val f = file(conversationId)
        val prior = if (f.isFile) read(f) else emptyList()
        if (prior == null) {
            // 损坏的文件挪到一边留着,不拿新内容覆盖掉
            f.renameTo(File(dir, "${f.name}.corrupt-${System.currentTimeMillis()}"))
        }
        val stored = if (turn.atMs == null) turn.copy(atMs = clock()) else turn
        write(conversationId, prior.orEmpty() + (stored to files))
    }

    /** 改运行编号为 [runId] 的那一轮(任务结束后补上判的结局、记忆与技能提示);找不到就不动。 */
    @Synchronized
    fun updateTurn(conversationId: String, runId: String, transform: (Turn) -> Turn) {
        val f = file(conversationId)
        if (!f.isFile) return
        val records = read(f) ?: return
        if (records.none { it.first.runId == runId }) return
        write(conversationId, records.map { (t, fs) -> (if (t.runId == runId) transform(t) else t) to fs })
    }

    /** 记忆删了或全部清除时:去掉指向这些条目的提示和「用到」记录;[ids] 为 null 时去掉全部。 */
    @Synchronized
    fun stripMemory(ids: Set<String>?) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
            val records = read(f) ?: return@forEach
            fun keep(id: String) = ids != null && id !in ids
            val stripped = records.map { (t, fs) -> t.copy(memoryNotes = t.memoryNotes.filter { keep(it.memoryId) }, usedMemory = t.usedMemory.filter(::keep)) to fs }
            if (stripped != records) write(f.name.removeSuffix(".json"), stripped)
        }
    }

    private fun write(conversationId: String, all: List<Pair<Turn, List<SharedFile>>>) {
        dir.mkdirs()
        val json = JsonArray(all.map { (it, fs) ->
            buildJsonObject {
                put("userText", it.userText); put("outcome", it.outcome); put("status", it.status)
                it.note?.let { n -> put("note", n) }; it.runId?.let { r -> put("runId", r) }; it.result?.let { r -> put("result", r) }
                it.verified?.let { v -> put("verified", v) }
                it.remoteTaskId?.let { id -> put("remoteTaskId", id) }
                // 只存种类和编号:记忆内容只在加密的记忆文件里,界面现取
                if (it.memoryNotes.isNotEmpty()) put("memoryNotes", JsonArray(it.memoryNotes.map { n ->
                    buildJsonObject {
                        put("kind", n.kind.name); put("memoryId", n.memoryId)
                        n.undoToken?.let { t -> put("undoToken", t) }; n.pendingId?.let { p -> put("pendingId", p) }
                    }
                }))
                if (it.usedMemory.isNotEmpty()) put("usedMemory", JsonArray(it.usedMemory.map(::JsonPrimitive)))
                if (it.unconfirmed.isNotEmpty()) put("unconfirmed", JsonArray(it.unconfirmed.map(::JsonPrimitive)))
                if (it.steers.isNotEmpty()) put("steers", JsonArray(it.steers.map(::JsonPrimitive)))
                if (it.interruptedReplies.any(String::isNotEmpty)) put("interruptedReplies", JsonArray(it.interruptedReplies.map(::JsonPrimitive)))
                if (it.finishedReplies.any(String::isNotEmpty)) put("finishedReplies", JsonArray(it.finishedReplies.map(::JsonPrimitive)))
                if (it.groups.isNotEmpty()) put("groups", JsonArray(it.groups.map(::JsonPrimitive)))
                it.keptPage?.let { h -> put("keptPage", h) }; it.keptPageClosed?.let { r -> put("keptPageClosed", r) }
                it.atMs?.let { t -> put("atMs", t) }
                if (it.skillNotes.isNotEmpty()) put("skillNotes", JsonArray(it.skillNotes.map { n ->
                    buildJsonObject { put("text", n.text); n.draftId?.let { d -> put("draftId", d) }; n.suspended?.let { x -> put("suspended", x) } }
                }))
                if (fs.isNotEmpty()) put("files", JsonArray(fs.map { f ->
                    buildJsonObject {
                        put("name", f.name); put("size", f.size); put("savedAs", f.savedAs); put("uri", f.uri)
                        f.sha256?.let { put("sha256", it) }
                    }
                }))
            }
        })
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(dir, "${file(conversationId).name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file(conversationId))) {
            file(conversationId).writeText(json.toString())
            tmp.delete()
        }
        firstMessages.remove(file(conversationId).name)
        _changes.tryEmit(conversationId)
    }

    private fun objects(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** App 进程里共用的那一份(读写都在它的锁里串行)。 */
        fun shared(context: Context): ConversationStore = (context.applicationContext as com.wangzan101.mika.MikaApp).conversations

        /** `current` 文件里表示「新开还没说话的对话」的那个值(对话编号只会是字母数字、`-`、`_`,不会撞上)。 */
        private const val CURRENT_FRESH = "fresh"
    }

    // id 来自 newConversation 或外部传入,只留安全字符,免得拼出目录外的路径
    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun titleFile(id: String) = File(dir, "${safe(id)}.title")
    private fun pinFile(id: String) = File(dir, "${safe(id)}.pin")
    private fun pageFile(id: String) = File(dir, "${safe(id)}.page")
    private fun branchFile(id: String) = File(dir, "${safe(id)}.branch")

    /** 用户此刻在哪个对话,见 [CurrentChat];不是 `.json`,不进 [list]。 */
    private fun currentFile() = File(dir, "current")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinnedAt: Long? = null,
    val page: PageOrigin? = null,
    val branchOf: BranchOrigin? = null,
)

/** 分支来源:从 [parentId] 的第 [parentTurn] 轮(0 起)分出来的;[afterTurn] 为 false 时是复制到这一轮之前(第 2 阶段从用户消息重新问)。 */
data class BranchOrigin(val parentId: String, val parentTurn: Int, val afterTurn: Boolean, val at: Long)

/**
 * 用户此刻在哪个对话:App 里新开、还没说过话的是 [Fresh],正在看的是 [Open];没有记录(没进过 Mika、旧版本)为 null。
 * 悬浮胶囊据此接着该接的那个,而不是按「哪个对话文件最后被写过」去猜——新开的对话还没有文件,猜出来的会是上一个。
 */
sealed interface CurrentChat {
    data object Fresh : CurrentChat

    data class Open(val id: String) : CurrentChat
}

/** 对话从哪一页开始:网址、主机名、页面标题(没有为空串)。 */
data class PageOrigin(val url: String, val host: String, val title: String = "")
