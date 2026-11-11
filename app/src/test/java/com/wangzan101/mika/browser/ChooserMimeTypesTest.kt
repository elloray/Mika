package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ChooserMimeTypesTest {
    private val mimeOf: (String) -> String? = { if (it == "pdf") "application/pdf" else null }

    @Test
    fun keepsMimeTypesAndResolvesExtensions() {
        assertEquals(listOf("image/*", "application/pdf"), ChooserMimeTypes.of(listOf("image/*", ".PDF"), mimeOf))
    }

    @Test
    fun unknownExtensionsAndBlanksAreDropped() {
        assertEquals(listOf("text/plain"), ChooserMimeTypes.of(listOf("", ".xyz", "text/plain "), mimeOf))
    }

    @Test
    fun emptyMeansEverything() {
        assertEquals(listOf("*/*"), ChooserMimeTypes.of(emptyList(), mimeOf))
        assertEquals(listOf("*/*"), ChooserMimeTypes.of(listOf("", ".xyz"), mimeOf))
    }
}
