package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** 数量或边界进度；总数未知时只报已确认的数量和最新边界。 */
internal data class ProgressMetric(
    val name: String,
    val done: Int? = null,
    val total: Int? = null,
    val boundary: String? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME)
        require(done == null || done >= 0)
        require(total == null || total > 0)
        require(done == null || total == null || done <= total)
        require(boundary == null || boundary.length <= MAX_BOUNDARY)
    }

    val reached: Boolean get() = done != null && total != null && done == total
    fun event() = RunEvent.Progress(name, done, total, boundary)
    fun state() = RunProgress.Task(name, done, total, boundary)
}

/** 本次任务的验收判据与最近确认的进度。[allCriteriaMet] 是模型申报，数量到达是代码判定。 */
internal data class CompletionPlan(
    val criteria: List<String> = emptyList(),
    val metric: ProgressMetric? = null,
    val allCriteriaMet: Boolean = false,
) {
    val complete: Boolean get() = allCriteriaMet || metric?.reached == true

    fun apply(u: CompletionUpdate): CompletionPlan {
        val metric = u.metric?.let { change ->
            val old = metric
            ProgressMetric(
                change.name ?: old?.name ?: throw IllegalArgumentException("metric.name is required"),
                change.done ?: old?.done,
                change.total ?: old?.total,
                change.boundary ?: old?.boundary,
            )
        } ?: metric
        return CompletionPlan(u.criteria ?: criteria, metric, u.allCriteriaMet ?: allCriteriaMet)
    }

    fun event(atMs: Long) = RunEvent.Criteria(atMs, criteria, emptyList(), progress = metric?.event(), complete = complete)
}

internal data class MetricUpdate(val name: String? = null, val done: Int? = null, val total: Int? = null, val boundary: String? = null)
internal data class CompletionUpdate(
    val criteria: List<String>? = null,
    val metric: MetricUpdate? = null,
    val allCriteriaMet: Boolean? = null,
)

/**
 * 主模型随实际工具调用或最终回答写出的 <task_progress> JSON 块。它不是工具，不增加请求或 schema；
 * 代码取走后，界面和后续历史只看到外面的普通说明。
 */
internal object CompletionPlanBlock {
    private const val OPEN = "<task_progress>"
    private val BLOCK = Regex("$OPEN(.*?)</task_progress>", RegexOption.DOT_MATCHES_ALL)
    private val ROOT_KEYS = setOf("criteria", "metric", "complete")
    private val METRIC_KEYS = setOf("name", "done", "total", "boundary")

    class Extracted(val text: String, val updates: List<CompletionUpdate>, val errors: List<String>)

    /** 流式文字也可能只到半个开始标记；完整块和末尾未写完的块都不显示。 */
    fun visible(text: String): String {
        val complete = BLOCK.replace(text, "")
        val incomplete = incompleteStart(complete)
        return if (incomplete < 0) complete else complete.substring(0, incomplete)
    }

    private fun incompleteStart(text: String): Int {
        val open = text.indexOf(OPEN)
        if (open >= 0) return open
        val last = text.lastIndexOf('<')
        if (last < 0) return -1
        val suffix = text.substring(last)
        return if (suffix.length >= 2 && OPEN.startsWith(suffix)) last else -1
    }

    fun extract(text: String): Extracted {
        val updates = mutableListOf<CompletionUpdate>()
        val errors = mutableListOf<String>()
        BLOCK.findAll(text).forEach { match ->
            val body = match.groupValues[1].trim()
            if (body.length > MAX_BLOCK) {
                errors += "task_progress block is too long"
                return@forEach
            }
            try {
                updates += parse(body)
            } catch (e: Exception) {
                errors += e.message ?: "invalid task_progress block"
            }
        }
        if (incompleteStart(BLOCK.replace(text, "")) >= 0) errors += "unclosed task_progress block"
        return Extracted(visible(text).trim(), updates, errors)
    }

    private fun parse(body: String): CompletionUpdate {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("task_progress must be a JSON object")
        require(root.keys.all { it in ROOT_KEYS }) { "unknown task_progress field" }
        val criteria = root["criteria"]?.let { value ->
            val items = value as? JsonArray ?: throw IllegalArgumentException("criteria must be an array")
            require(items.size in 1..MAX_CRITERIA) { "criteria must contain 1..$MAX_CRITERIA items" }
            items.map { item ->
                val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    ?: throw IllegalArgumentException("criterion must be a string")
                require(text.isNotEmpty() && text.length <= MAX_CRITERION) { "criterion is empty or too long" }
                text
            }
        }
        val metric = root["metric"]?.let { value ->
            val obj = value as? JsonObject ?: throw IllegalArgumentException("metric must be an object")
            require(obj.keys.all { it in METRIC_KEYS }) { "unknown metric field" }
            require(obj.isNotEmpty()) { "metric must not be empty" }
            MetricUpdate(
                obj.string("name")?.also { require(it.isNotBlank() && it.length <= MAX_NAME) { "invalid metric.name" } },
                obj.int("done")?.also { require(it >= 0) { "metric.done must be non-negative" } },
                obj.int("total")?.also { require(it > 0) { "metric.total must be positive" } },
                obj.string("boundary")?.also { require(it.length <= MAX_BOUNDARY) { "metric.boundary is too long" } },
            ).also { change ->
                if (change.done != null && change.total != null) require(change.done <= change.total) { "metric.done exceeds total" }
            }
        }
        val complete = (root["complete"] as? JsonPrimitive)?.booleanOrNull
        if (root.containsKey("complete")) require(complete != null) { "complete must be boolean" }
        require(criteria != null || metric != null || complete != null) { "empty task_progress update" }
        return CompletionUpdate(criteria, metric, complete)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.let {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: throw IllegalArgumentException("$key must be a string")
    }

    private fun JsonObject.int(key: String): Int? = this[key]?.let {
        (it as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("$key must be an integer")
    }

    private const val MAX_BLOCK = 8_000
    private const val MAX_CRITERIA = 12
    private const val MAX_CRITERION = 300
}

private const val MAX_NAME = 80
private const val MAX_BOUNDARY = 200

/** 连续没有新数据的采集停止。只有真实观察能重置计数，模型写的进度说明不能。 */
internal class NewDataTracker {
    private val seen = mutableSetOf<Long>()
    var stale = 0
        private set

    /** 返回 true 表示已到连续无新数据上限。失败、没有文字的调用不打断也不增加计数。 */
    fun after(verb: Verb?, exec: ToolExecution): Boolean {
        if (verb !in COLLECTION_VERBS || exec.content["ok"] != JsonPrimitive(true)) return false
        val reads = exec.observations.filter { it.kind != ObsKind.Fact && it.text.isNotBlank() }
        if (reads.isEmpty()) return false
        val fresh = reads.map { seen.add(fingerprint(it)) }.any { it }
        if (fresh) {
            stale = 0
            return false
        }
        stale++
        return stale >= LIMIT
    }

    private fun fingerprint(o: Observation): Long {
        var h = -0x340d631b7bdddcdbL
        val value = listOf(o.kind.wire, o.place?.kind?.wire.orEmpty(), o.place?.name.orEmpty(), o.text).joinToString("\u0000")
        for (b in value.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
        return h
    }

    companion object {
        const val LIMIT = 3
        private val COLLECTION_VERBS = setOf(
            Verb.Observe, Verb.ListElements, Verb.Query, Verb.Collect, Verb.PhoneExtract,
            Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand, Verb.Scroll, Verb.Swipe,
        )
    }
}
