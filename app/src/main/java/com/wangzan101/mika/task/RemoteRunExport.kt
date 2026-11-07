package com.wangzan101.mika.task

import com.wangzan101.mika.core.remote.RunExportLookup
import com.wangzan101.mika.core.remote.RunExportRecord
import com.wangzan101.mika.core.remote.RunExportSource
import com.wangzan101.mika.core.remote.RunRecordExport
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.record.RunStore

/** 电脑任务编号和它的运行记录怎么对上。只认 [RemoteTaskHub] 里登记过的任务。 */
interface RemoteRunIndex {
    fun scopeIndex(owner: String): RemoteRunIndex = this
    fun task(id: String): RemoteTask?
    /** 运行记录编号;还没开记录时为 null。进行中的任务也会从还活着的那次运行上取。 */
    fun rememberedRunId(taskId: String): String?
    /** 这条运行记录属于哪个电脑任务;收件里没有对应,或对话已删,为 null。 */
    fun taskForRun(runId: String): RemoteTask?
}

/**
 * 给任务 API 的运行记录出口:按任务编号或运行记录编号读 [RunStore] 里已经落盘的 run.json 和 events.jsonl。
 * 手机上发起的运行不导出。不读截图文件。
 */
class RemoteRunExport(
    private val index: RemoteRunIndex,
    private val store: () -> RunStore,
) : RunExportSource {
    override fun scope(owner: String): RunExportSource = RemoteRunExport(index.scopeIndex(owner), store)

    override fun load(id: String): RunExportLookup {
        val clean = id.trim()
        if (clean.isEmpty()) return RunExportLookup.Missing
        index.task(clean)?.let { return forTask(it) }
        if (!RunRecordExport.RUN_ID.matches(clean)) return RunExportLookup.Missing
        val runs = store()
        val summary = runs.summary(clean) ?: return RunExportLookup.Missing
        val known = index.taskForRun(clean) ?: return RunExportLookup.Missing
        // origin 只是运行元数据,绝不是所有权索引;索引缺失时一律不可导出。
        return RunExportLookup.Found(RunExportRecord(known.id, known.conversation, clean, summary, runs.events(clean)))
    }

    private fun forTask(task: RemoteTask): RunExportLookup {
        val runId = index.rememberedRunId(task.id)
            ?: return RunExportLookup.Found(RunExportRecord(task.id, task.conversation, null, null, emptyList()))
        val runs = store()
        val summary = runs.summary(runId) ?: return RunExportLookup.Gone(task.id, runId)
        return RunExportLookup.Found(RunExportRecord(task.id, task.conversation, runId, summary, runs.events(runId)))
    }
}
