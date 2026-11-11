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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
private data class ConditionPlanSnapshot(
    val states: Map<String, ConditionPlanState> = emptyMap(),
    val primaryHash: String? = null,
    val previous: ConditionPlanVersion? = null,
) {
    fun forPrimary(hash: String): Map<String, ConditionPlanState> = when {
        primaryHash == null || primaryHash == hash -> states
        previous?.primaryHash == hash -> previous.states
        else -> emptyMap()
    }
}

@Serializable
private data class ConditionPlanVersion(val primaryHash: String, val states: Map<String, ConditionPlanState>)

private val CONDITION_JSON = Json { ignoreUnknownKeys = true }

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun TriggerDefinition.conditionState() = ConditionPlanState(
    notifyWhen, cooldownMs, lastValue, baselineValue, baselineAtMs, lastFiredAtMs, calendarTimes,
)

private fun TriggerSnapshot.conditionStates(): Map<String, ConditionPlanState> =
    triggers.filter { it.conditionGoal != null || it.calendarTimes.isNotEmpty() }
        .associate { it.id to it.conditionState() }

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
        cooldownMs = state.cooldownMs?.takeIf { it > 0 },
        lastValue = state.lastValue,
        baselineValue = state.baselineValue,
        baselineAtMs = state.baselineAtMs,
        lastFiredAtMs = state.lastFiredAtMs,
    )
}

/**
 * 触发器和时段账本同文件原子替换；认领时段落盘成功后才返回给派发器。
 * 扩展状态存于 sidecar，按主文件内容选择同一次提交的状态，主文件保持旧版格式。
 */
class FileTriggerStore(
    private val file: File,
    private val conditionFile: File = File(file.parentFile, file.name + ".conditions"),
) : TriggerStore {
    private data class Committed(val primaryHash: String?, val snapshot: TriggerSnapshot)

    private var committed = load()
    private var memory = MemoryTriggerStore(committed.snapshot)

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

    private fun load(): Committed {
        val empty = Committed(null, TriggerSnapshot(emptyList(), emptyList()))
        if (!file.isFile) return empty
        val bytes: ByteArray
        val snapshot: TriggerSnapshot
        try {
            bytes = file.readBytes()
            snapshot = Json.decodeFromString<TriggerSnapshot>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            return empty
        }
        val hash = bytes.sha256()
        val states = readConditionSnapshot().forPrimary(hash)
        return Committed(hash, snapshot.copy(
            triggers = snapshot.triggers.map { it.withConditionState(states[it.id]) },
        ))
    }

    private fun readConditionSnapshot(): ConditionPlanSnapshot {
        if (!conditionFile.isFile) return ConditionPlanSnapshot()
        return runCatching {
            CONDITION_JSON.decodeFromString<ConditionPlanSnapshot>(conditionFile.readText())
        }.getOrElse {
            conditionFile.copyTo(File(conditionFile.parentFile, conditionFile.name + ".corrupt"), overwrite = true)
            ConditionPlanSnapshot()
        }
    }

    private fun save() {
        val snapshot = memory.snapshot()
        try {
            val bytes = Json.encodeToString(snapshot.copy(triggers = snapshot.triggers.map { it.legacy() }))
                .toByteArray(Charsets.UTF_8)
            val hash = bytes.sha256()
            val previous = committed.primaryHash?.takeIf { it != hash }
                ?.let { ConditionPlanVersion(it, committed.snapshot.conditionStates()) }
            val state = ConditionPlanSnapshot(snapshot.conditionStates(), hash, previous)
            // Publish both versions before switching the primary. For identical primary contents,
            // the sidecar replacement itself commits edits to the extension fields.
            writeAtomic(conditionFile, CONDITION_JSON.encodeToString(state).toByteArray(Charsets.UTF_8))
            if (committed.primaryHash != hash) writeAtomic(file, bytes)
            committed = Committed(hash, snapshot)
        } catch (e: Exception) {
            memory = MemoryTriggerStore(committed.snapshot)
            throw e
        }
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
