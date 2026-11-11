package com.wangzan101.mika.browser

/** 网页文件选择框给的类型 → 系统选择器的 MIME 类型。 */
object ChooserMimeTypes {
    /**
     * 扩展名(以 `.` 开头)交给 [mimeOf] 换成 MIME,其他原样;空的丢掉;一个都没有时用全部类型。
     */
    fun of(acceptTypes: List<String>, mimeOf: (String) -> String?): List<String> {
        val types = acceptTypes.mapNotNull { raw ->
            val t = raw.trim()
            when {
                t.isEmpty() -> null
                t.startsWith(".") -> mimeOf(t.removePrefix(".").lowercase())
                else -> t
            }
        }.distinct()
        return types.ifEmpty { listOf("*/*") }
    }
}
