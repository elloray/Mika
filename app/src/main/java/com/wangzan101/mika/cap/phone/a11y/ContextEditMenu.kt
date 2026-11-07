package com.wangzan101.mika.cap.phone.a11y

/** Recognize the platform's Paste command without relying on any app's text. */
internal class ContextEditMenu(private val pasteLabel: String) {
    fun isPaste(resourceId: String?, role: String?, label: String?): Boolean =
        resourceId == "android:id/paste" || role == "Button" && label == pasteLabel

    /** Other new controls can indicate a selection or an unrelated menu. */
    fun <T> freshPaste(before: Set<T>, nearby: List<T>, isPaste: (T) -> Boolean): T? {
        val candidate = nearby.filterNot { it in before }.singleOrNull() ?: return null
        return candidate.takeIf(isPaste)?.takeIf { nearby.count(isPaste) == 1 }
    }
}
