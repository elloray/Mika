package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/** 排队项的来历:用户明确选了「排队」,或引导没赶上当前任务收尾、只能排到下一件。 */
enum class QueuedSource { Explicit, Fallback }

/**
 * 这个对话里等着下一件开始的话:用户选了排队的,或没来得及交给当前任务的引导。
 * [claimed] 只在内存里:服务已经取走、正在登记运行;进程此时被杀,重启后它仍是待处理(宁可重跑,不能丢)。
 */
data class QueuedFollowUp(
    val id: String,
    val conversationId: String,
    val text: String,
    val createdAt: Long = 0L,
    val source: QueuedSource = QueuedSource.Explicit,
    val claimed: Boolean = false,
    /** 排队不能改变任务身份；恢复后仍按原渠道执行、回复并应用对应的解锁策略。 */
    val provenance: RunProvenance = RunProvenance(),
    val channelPeer: ChannelPeer? = null,
    val channelMessageId: String? = null,
)

/** 排队项的落盘方式,测试里换成内存实现。 */
interface FollowUpQueueStore {
    fun load(): List<QueuedFollowUp>
    fun save(items: List<QueuedFollowUp>)
}

/** 只存在内存里的队列,测试和 `FollowUpQueue` 的默认依赖用。 */
class MemoryFollowUpQueueStore(initial: List<QueuedFollowUp> = emptyList()) : FollowUpQueueStore {
    private var items = initial.toList()
    @Synchronized override fun load(): List<QueuedFollowUp> = items
    @Synchronized override fun save(items: List<QueuedFollowUp>) { this.items = items.toList() }
}

/**
 * App 私有目录里的排队账本:每次增删先写临时文件、落盘、再原子替换。
 * 读不出来时把原文件挪到 `.corrupt-时间戳` 留着,当作空队列,不覆盖用户可能还想看的文件。
 * `claimed` 不落盘:进程在取走之后、运行登记之前被杀,重启后这条仍是待处理。
 */
class FileFollowUpQueueStore(private val file: File) : FollowUpQueueStore {
    @Synchronized override fun load(): List<QueuedFollowUp> {
        if (!file.isFile) return emptyList()
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val conversationId = o["conversationId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                QueuedFollowUp(
                    id = id,
                    conversationId = conversationId,
                    text = text,
                    createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    source = QueuedSource.entries.firstOrNull { it.name == o["source"]?.jsonPrimitive?.contentOrNull }
                        ?: QueuedSource.Explicit,
                    provenance = provenance(o),
                    channelPeer = channelPeer(o),
                    channelMessageId = o["channelMessageId"]?.jsonPrimitive?.contentOrNull,
                )
            }.distinctBy { it.id }
        }.getOrElse {
            // 坏文件留着,别让下一次写入把它盖掉;同一毫秒重复时加个序号
            var backup = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
            var n = 1
            while (backup.exists()) backup = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis() + "-" + n++)
            runCatching { file.renameTo(backup) }
            emptyList()
        }
    }

    @Synchronized override fun save(items: List<QueuedFollowUp>) {
        file.parentFile?.mkdirs()
        val json = JsonArray(items.map { q ->
            buildJsonObject {
                put("id", q.id)
                put("conversationId", q.conversationId)
                put("text", q.text)
                put("createdAt", q.createdAt)
                put("source", q.source.name)
                put("provenance", buildJsonObject {
                    put("origin", q.provenance.origin.name)
                    put("attendance", q.provenance.attendance.name)
                })
                q.channelPeer?.let { peer -> put("channelPeer", buildJsonObject {
                    put("channelId", peer.channelId)
                    put("peerId", peer.peerId)
                    put("threadId", peer.threadId)
                }) }
                put("channelMessageId", q.channelMessageId)
            }
        }).toString()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        if (!temp.renameTo(file)) {
            temp.delete()
            error("follow-up queue rename failed")
        }
    }

    private fun provenance(o: JsonObject): RunProvenance {
        val value = o["provenance"] as? JsonObject ?: return RunProvenance()
        return RunProvenance(
            origin = RunOrigin.entries.firstOrNull { it.name == value["origin"]?.jsonPrimitive?.contentOrNull }
                ?: RunOrigin.Ui,
            attendance = Attendance.entries.firstOrNull { it.name == value["attendance"]?.jsonPrimitive?.contentOrNull }
                ?: Attendance.Present,
        )
    }

    private fun channelPeer(o: JsonObject): ChannelPeer? {
        val peer = o["channelPeer"] as? JsonObject ?: return null
        return runCatching {
            ChannelPeer(
                peer.getValue("channelId").jsonPrimitive.content,
                peer.getValue("peerId").jsonPrimitive.content,
                peer.getValue("threadId").jsonPrimitive.content,
            )
        }.getOrNull()
    }
}

/**
 * 同对话下一件任务的持久 FIFO:用户明确排队的和引导 fallback 都在这里。
 * 服务按对话一次只取一件([claimNext]);取走后登记运行,真正开始执行时再 [remove]。
 * `changes` 是界面看到的唯一来源(包括已 claim、还没 remove 的项,界面按 `claimed` 隐藏)。
 */
class FollowUpQueue(private val store: FollowUpQueueStore = MemoryFollowUpQueueStore()) {
    private val _changes = MutableStateFlow(store.load())
    val changes: StateFlow<List<QueuedFollowUp>> = _changes.asStateFlow()

    /** 按 id 幂等:同一句又入队一次时换成未 claim 的新版本(发送与收尾赛跑用)。 */
    @Synchronized
    fun add(item: QueuedFollowUp): Boolean {
        val current = _changes.value
        val next = if (current.any { it.id == item.id }) {
            current.map { if (it.id == item.id) item.copy(claimed = false) else it }
        } else current + item.copy(claimed = false)
        return publish(next)
    }

    /** 用户取消:已经 claim(正在开始)的不能取消。 */
    @Synchronized
    fun cancel(id: String): QueuedFollowUp? {
        val item = _changes.value.firstOrNull { it.id == id && !it.claimed } ?: return null
        return if (publish(_changes.value.filterNot { it.id == id })) item else null
    }

    /**
     * 取下一件:只给所在对话空闲、且还没 claim 的项;给了 [onlyConversation] 时只看那个对话。
     * 不给对话时每个对话至多取一件(按队列先后)。claim 不落盘:登记前死亡会重试,登记后由运行记录里的 followUpId 去重。
     */
    @Synchronized
    fun claimNext(isFree: (String) -> Boolean, onlyConversation: String? = null,
        alreadyStarted: (QueuedFollowUp) -> Boolean = { false }): List<QueuedFollowUp> {
        val picked = mutableListOf<QueuedFollowUp>()
        val conversations = mutableSetOf<String>()
        for (item in _changes.value) {
            if (item.claimed || alreadyStarted(item)) continue
            if (onlyConversation != null && item.conversationId != onlyConversation) continue
            if (!conversations.add(item.conversationId)) continue
            if (!isFree(item.conversationId)) { conversations.remove(item.conversationId); continue }
            picked += item
            if (onlyConversation != null) break
        }
        if (picked.isEmpty()) return emptyList()
        val ids = picked.map { it.id }.toSet()
        _changes.value = _changes.value.map { if (it.id in ids) it.copy(claimed = true) else it }
        return picked
    }

    /** 运行开始执行、已登记运行需要恢复去重,或容量排队被取消:把这一项从账本里拿掉并落盘。 */
    @Synchronized
    fun remove(id: String): Boolean {
        val current = _changes.value
        if (current.none { it.id == id }) return false
        return publish(current.filterNot { it.id == id })
    }

    @Synchronized
    fun dropConversation(conversationId: String) {
        val current = _changes.value
        if (current.none { it.conversationId == conversationId }) return
        publish(current.filterNot { it.conversationId == conversationId })
    }

    /** 这个对话还没被取走的排队项,按先后。 */
    @Synchronized
    fun pendingFor(conversationId: String): List<QueuedFollowUp> =
        _changes.value.filter { it.conversationId == conversationId && !it.claimed }

    @Synchronized
    fun containsId(id: String): Boolean = _changes.value.any { it.id == id }

    @Synchronized
    fun contains(conversationId: String): Boolean =
        _changes.value.any { it.conversationId == conversationId && !it.claimed }

    /** 先落盘再发布内存状态；写盘失败时调用方不得把这一项报告为已持久排队。 */
    private fun publish(items: List<QueuedFollowUp>): Boolean {
        val next = items.toList()
        return runCatching {
            store.save(next)
            _changes.value = next
        }.isSuccess
    }
}
