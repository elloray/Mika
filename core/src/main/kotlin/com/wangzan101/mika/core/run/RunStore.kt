package com.wangzan101.mika.core.run

/** 运行状态的最小持久化记录。运行中断后只告知并允许重跑，不从屏幕中途续跑。 */
data class StoredRun(
    val id: RunId,
    val threadId: String,
    val goal: String,
    val phase: RunPhase,
    val reply: String? = null,
    val deliveryId: String? = null,
    val roundInProgress: Boolean = false,
    val triggerWindowKey: String? = null,
    val runSucceeded: Boolean? = null,
    /** 非本机任务的回复地址与入站编号；旧记录缺省时仍按本机任务恢复。 */
    val channelPeer: ChannelPeer? = null,
    val channelMessageId: String? = null,
    /** 给人看、记进对话的原话;条件检查运行的 [goal] 是生成的检查目标,这里存用户写的条件原文。 */
    val userText: String? = null,
    /** 持久排队项的编号;恢复时据此防止已登记的运行被再次入队执行。 */
    val followUpId: String? = null,
    /** 详细运行记录的编号;进程中断后只读它生成交代,不据此重放工具调用。 */
    val recordId: String? = null,
)

interface RunStore {
    fun save(record: StoredRun)
    fun get(id: RunId): StoredRun?
    fun all(): List<StoredRun>

    /** 重启时逐个标记中断，不能静默把工具调用再跑一遍。[live] 里是此刻还在跑的运行,不动它们。 */
    fun interruptUnfinished(live: Set<RunId> = emptySet()): List<StoredRun> {
        val interrupted = all().filter { it.phase != RunPhase.Ended && it.phase != RunPhase.Interrupted && it.id !in live }
        interrupted.forEach { save(it.copy(phase = RunPhase.Interrupted)) }
        return interrupted
    }
}

class MemoryRunStore : RunStore {
    private val records = linkedMapOf<RunId, StoredRun>()
    @Synchronized override fun save(record: StoredRun) { records[record.id] = record }
    @Synchronized override fun get(id: RunId): StoredRun? = records[id]
    @Synchronized override fun all(): List<StoredRun> = records.values.toList()
}
