package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.chat.SharedDraft
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedImageDeviceTest {
    /** 走真实 ACTION_SEND、系统内容 URI 和生产 Activity,确认图片只进私有草稿,不自动启动任务。 */
    @Test
    fun imageShareBecomesPrivateDraftWithoutStartingTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val app = target.applicationContext as MikaApp
        val encoded = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(36, 102, 180)) }
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
        bitmap.recycle()
        val bytes = encoded.toByteArray()
        val resolver = target.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mika-share-${SystemClock.uptimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val text = "图片分享草稿验证"
            val intent = Intent(Intent.ACTION_SEND).apply {
                setClassName(target.packageName, MainActivity::class.java.name)
                type = "image/png"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, uri)
                // 普通分享方会把 EXTRA_STREAM 迁到 ClipData;显式设置后不依赖 adb am 的额外行为。
                clipData = ClipData.newRawUri("shared image", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val runsBefore = app.taskState.runs.value.map { it.runId.value }.toSet()
            var received: SharedDraft? = null
            val scenario = ActivityScenario.launch<MainActivity>(intent)
            try {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    received = app.sharedDrafts.read()?.takeIf { it.text == text && it.image != null }
                    if (received != null) break
                    SystemClock.sleep(100)
                }
                assertNotNull("The production activity did not save the shared image draft", received)
                val draft = requireNotNull(received)
                val image = requireNotNull(draft.image)
                assertEquals(bytes.size.toLong(), image.size)
                val privateCopy = requireNotNull(app.sharedDrafts.imageFile(image.fileName))
                assertArrayEquals(bytes, privateCopy.readBytes())
                val draftDir = File(target.filesDir, "shared-drafts").canonicalFile
                assertTrue(privateCopy.canonicalFile.toPath().startsWith(draftDir.toPath()))
                assertEquals("Sharing a draft must not start a task", runsBefore,
                    app.taskState.runs.value.map { it.runId.value }.toSet())
            } finally {
                app.sharedDrafts.read()?.takeIf { it.text == text }?.let { app.sharedDrafts.clear(it.conversationId) }
                runCatching { scenario.onActivity { it.finish() } }
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    /** 界面已在后台存活时收到分享:空输入框直接填入当前会话,已有草稿时等待用户确认替换或取消。 */
    @Test
    fun shareWhileActivityAliveTargetsCurrentConversationAndPromptsWhenDraftExists() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val app = target.applicationContext as MikaApp
        val launchIntent = Intent(target, MainActivity::class.java)
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        try {
            val conversationId = app.conversations.newConversation()
            scenario.onActivity {
                app.chat.openConversation(conversationId)
                app.chat.input("")
            }
            instrumentation.waitForIdleSync()

            val firstText = "后台分享直接进当前会话"
            val firstShare = Intent(Intent.ACTION_SEND).apply {
                setClassName(target.packageName, MainActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, firstText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            target.startActivity(firstShare)
            val deadline1 = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline1 && app.chat.ui.value.input != firstText) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertEquals(conversationId, app.chat.ui.value.conversationId)
            assertEquals(firstText, app.chat.ui.value.input)
            assertNull(app.chat.ui.value.pendingSharedDraft)

            val secondText = "后台分享需要确认替换"
            val secondShare = Intent(Intent.ACTION_SEND).apply {
                setClassName(target.packageName, MainActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, secondText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            target.startActivity(secondShare)
            val deadline2 = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline2 && app.chat.ui.value.pendingSharedDraft == null) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertEquals(conversationId, app.chat.ui.value.conversationId)
            assertEquals(firstText, app.chat.ui.value.input)
            assertEquals(secondText, app.chat.ui.value.pendingSharedDraft?.text)

            val missingImage = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
            scenario.onActivity { instrumentation.callActivityOnNewIntent(it, missingImage) }
            assertEquals(secondText, app.chat.ui.value.pendingSharedDraft?.text)
            assertEquals(secondText, app.sharedDrafts.readPending()?.text)

            scenario.onActivity { app.chat.confirmPendingSharedDraft() }
            instrumentation.waitForIdleSync()
            assertEquals(conversationId, app.chat.ui.value.conversationId)
            assertEquals(secondText, app.chat.ui.value.input)
            assertNull(app.chat.ui.value.pendingSharedDraft)
        } finally {
            app.chat.cancelPendingSharedDraft()
            app.chat.ui.value.conversationId?.let { app.sharedDrafts.clear(it) }
            runCatching { scenario.onActivity { it.finish() } }
        }
    }
}
