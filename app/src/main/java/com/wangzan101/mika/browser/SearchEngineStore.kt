package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 浏览器可选的搜索引擎:当前选的和用户自己加的。 */
data class SearchEngineChoices(val selected: SearchEngineSpec, val custom: List<CustomSearchEngine>) {
    /** 选择列表里的全部:内置在前,自定义在后。 */
    val all: List<SearchEngineSpec> get() = SearchEngine.entries + custom
}

/**
 * 用户在起始页选的搜索引擎和自定义的搜索引擎,下次打开还是这样。
 * 存在 App 私有目录的一个 JSON 文件里;文件不存在、坏了或认不出来时用 [SearchEngine.Bing]、没有自定义。
 */
class SearchEngineStore(dir: File) {
    private val file = File(dir, FILE)
    private val _choices = MutableStateFlow(load())

    val choices: StateFlow<SearchEngineChoices> = _choices.asStateFlow()

    @Synchronized
    fun set(engine: SearchEngineSpec) {
        val c = _choices.value
        if (c.selected.id == engine.id) return
        _choices.value = c.copy(selected = engine)
        save(_choices.value)
    }

    /** 加一个自定义搜索引擎并换成它;同一个 id 已经有了就不动。 */
    @Synchronized
    fun addCustom(engine: CustomSearchEngine) {
        val c = _choices.value
        if (c.custom.any { it.id == engine.id }) return
        _choices.value = SearchEngineChoices(engine, c.custom + engine)
        save(_choices.value)
    }

    /** 删掉一个自定义搜索引擎;删的是当前选的就换回必应。 */
    @Synchronized
    fun removeCustom(id: String) {
        val c = _choices.value
        val rest = c.custom.filterNot { it.id == id }
        if (rest.size == c.custom.size) return
        val selected = if (c.selected.id == id) SearchEngine.Bing else c.selected
        _choices.value = SearchEngineChoices(selected, rest)
        save(_choices.value)
    }

    private fun load(): SearchEngineChoices {
        if (!file.isFile) return SearchEngineChoices(SearchEngine.Bing, emptyList())
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching null
            val custom = (root["custom"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val template = (o["template"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                CustomSearchEngine.create(id, name, template)
            }.orEmpty().distinctBy { it.id }
            val selectedId = (root["selected"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            SearchEngineChoices(custom.firstOrNull { it.id == selectedId } ?: SearchEngine.fromId(selectedId) ?: SearchEngine.Bing, custom)
        }.getOrNull() ?: SearchEngineChoices(SearchEngine.Bing, emptyList())
    }

    private fun save(choices: SearchEngineChoices) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = buildJsonObject {
                put("selected", choices.selected.id)
                put(
                    "custom",
                    JsonArray(
                        choices.custom.map { e ->
                            buildJsonObject {
                                put("id", e.id)
                                put("name", e.displayName)
                                put("template", e.template)
                            }
                        },
                    ),
                )
            }.toString()
            // 先写临时文件再改名,写到一半被杀不会留下半个文件
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "browser_search_engines.json"
    }
}
