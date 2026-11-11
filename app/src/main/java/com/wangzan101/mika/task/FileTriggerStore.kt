package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.MemoryTriggerStore
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerStore
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.trigger.normalizedCalendarTimes
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** 计划扩展状态单独落盘；旧版仍能读主文件，多钟点降级为首个钟点。 */
@Serializable
private data class ConditionPlanState(
    val notifyWhen: ConditionNotify = ConditionNotify.BecomesMet,
    val cooldownMs: Long? = null,
    val lastValue: String? = null,
    val baselineValue: String? = null,
    val baselineAtMs: Long? = null,
    val lastFiredAtMs: Long? = null,
    val calendarTimes: List<String> = emptyList(),
)

@Serializable
private data class ConditionPlanSnapshot(val states: Map<String, ConditionPlanState> = emptyMap())

private val CONDITION_JSON = Json { ignoreUnknownKeys = true }

private fun TriggerDefinition.conditionState() = ConditionPlanState(
    notifyWhen, cooldownMs, lastValue, baselineValue, baselineAtMs, lastFiredAtMs, calendarTimes,
)

/** 主文件保持旧版 schema；新增字段只能进 sidecar。 */
private fun TriggerDefinition.legacy() = copy(
    notifyWhen = ConditionNotify.BecomesMet,
    cooldownMs = null,
    lastValue = null,
    baselineValue = null,
    baselineAtMs = null,
    lastFiredAtMs = null,
    calendarTimes = emptyList(),
)

private fun TriggerDefinition.withConditionState(state: ConditionPlanState?): TriggerDefinition {
    if (state == null) return this
    // 旧版改过首个钟点、或扩展列表损坏时，沿用主文件，不让 sidecar 破坏整份计划。
    val times = state.calendarTimes.takeIf { it.firstOrNull() == calendarTime &&
        runCatching { normalizedCalendarTimes(it) == it }.getOrDefault(false) }.orEmpty()
    val plan = copy(calendarTimes = times)
    if (conditionGoal == null) return plan
    return plan.copy(
        notifyWhen = state.notifyWhen,
        cooldownMs = state.cooldownMs,
        lastValue = state.lastValue,
        baselineValue = state.baselineValue,
        baselineAtMs = state.baselineAtMs,
        lastFiredAtMs = state.lastFiredAtMs,
    )
}

/**
 * 触发器和时段账本同文件原子替换；认领时段落盘成功后才返回给派发器。
 * 条件观测值、基准与冷却在独立 sidecar 中：升级再降级不会清空主计划文件，sidecar 损坏也只影响扩展状态。
 */
class FileTriggerStore(
    private val file: File,
    private val conditionFile: File = File(file.parentFile, file.name + ".conditions"),
) : TriggerStore {
    private val conditionStates = readConditionStates()
    private val memory = MemoryTriggerStore(if (file.isFile) runCatching {
        Json.decodeFromString<TriggerSnapshot>(file.readText()).let { snapshot ->
            snapshot.copy(triggers = snapshot.triggers.map { it.withConditionState(conditionStates[it.id]) })
        }
    }.getOrElse {
        file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
        TriggerSnapshot(emptyList(), emptyList())
    } else TriggerSnapshot(emptyList(), emptyList()))

    @Synchronized override fun upsert(trigger: TriggerDefinition) { memory.upsert(trigger); save() }
    @Synchronized fun edit(trigger: TriggerDefinition): Boolean = memory.edit(trigger).also { if (it) save() }
    @Synchronized override fun remove(id: String) { memory.remove(id); save() }
    @Synchronized override fun get(id: String) = memory.get(id)
    @Synchronized override fun all() = memory.all()
    @Synchronized override fun windows() = memory.windows()
    @Synchronized override fun claimDue(nowMs: Long, eligible: (TriggerDefinition) -> Boolean): List<TriggerWindow> =
        memory.claimDue(nowMs, eligible).also { if (it.isNotEmpty()) save() }
    @Synchronized override fun claimNow(id: String, nowMs: Long): TriggerWindow? =
        memory.claimNow(id, nowMs)?.also { save() }
    @Synchronized override fun mark(key: String, state: TriggerWindowState, reason: String?): Boolean =
        memory.mark(key, state, reason).also { if (it) save() }
    @Synchronized override fun retireDeliveredOneShots() { memory.retireDeliveredOneShots(); save() }

    private fun readConditionStates(): MutableMap<String, ConditionPlanState> {
        if (!conditionFile.isFile) return mutableMapOf()
        return runCatching {
            CONDITION_JSON.decodeFromString<ConditionPlanSnapshot>(conditionFile.readText()).states.toMutableMap()
        }.getOrElse {
            conditionFile.copyTo(File(conditionFile.parentFile, conditionFile.name + ".corrupt"), overwrite = true)
            mutableMapOf()
        }
    }

    private fun save() {
        val snapshot = memory.snapshot()
        writeAtomic(file, Json.encodeToString(snapshot.copy(triggers = snapshot.triggers.map { it.legacy() })))
        conditionStates.clear()
        snapshot.triggers.filter { it.conditionGoal != null || it.calendarTimes.isNotEmpty() }
            .forEach { conditionStates[it.id] = it.conditionState() }
        writeAtomic(conditionFile, CONDITION_JSON.encodeToString(ConditionPlanSnapshot(conditionStates.toMap())))
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(temp.renameTo(target)) { "trigger store rename failed" }
    }
}
