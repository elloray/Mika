package com.wangzan101.mika.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** 删掉一个对话会连带什么:有没有在跑或排队的任务(会被停掉),绑在它上面的定时任务有几条(会保留)。 */
data class DeleteImpact(val runningTask: Boolean, val schedules: Int)

/** 一次批量删除的进度:一共 [total] 条,已经处理 [done] 条,其中 [failed] 条没删成。 */
data class DeleteBatch(val total: Int, val done: Int, val failed: Int) {
    /** 全部处理完了(不论成没成)。 */
    val finished: Boolean get() = done >= total
}

/**
 * 删掉一个对话:停掉它在跑、排队的任务并等它收尾(收尾时存进的那一轮也在删的范围里),
 * 然后删它的工作区(附件、用户发来的屏幕、任务写的文件)和对话记录。
 * 定时任务有自己的管理页,不随历史对话删除;下次触发会在原对话编号下重新留下结果。
 * 运行记录(运行统计用)是全局的,不删。
 */
class ConversationDeleter(
    private val store: ConversationStore,
    /** 有任务在跑或排队的对话。 */
    private val active: StateFlow<List<String>>,
    /** 停掉这个对话所有在跑、排队的任务。 */
    private val cancelTasks: (conversationId: String) -> Unit,
    /** 绑在这个对话上的定时任务的编号;只用于删除前提示,计划本身保留。 */
    private val schedulesOf: (conversationId: String) -> List<String>,
    /** 删掉这个对话的工作区。 */
    private val deleteWorkspace: suspend (conversationId: String) -> Unit,
    /** 停任务后最多等它收尾这么久;过了照删。 */
    private val stopWaitMs: Long = 15_000,
    /** 读写定时任务和对话记录在哪儿做。 */
    private val io: CoroutineContext = Dispatchers.IO,
    private val forgetRemoteTasks: (String) -> Unit = {},
) {
    fun impact(conversationId: String): DeleteImpact =
        DeleteImpact(conversationId in active.value, runCatching { schedulesOf(conversationId).size }.getOrDefault(0))

    suspend fun delete(conversationId: String) {
        if (conversationId in active.value) {
            cancelTasks(conversationId)
            withTimeoutOrNull(stopWaitMs) { active.first { conversationId !in it } }
        }
        // 工作区删不掉(运行环境不支持等)也照样删对话记录
        try { deleteWorkspace(conversationId) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        withContext(io) {
            forgetRemoteTasks(conversationId)
            store.delete(conversationId)
        }
    }
}
