package com.wangzan101.mika.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** 分支工作区只复制 `inbox/` 里的普通文件;符号链接、inbox 外的文件都不进去。纯 JVM,不依赖 Android。 */
class ConversationBrancherTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun copiesInboxFilesAndStructureOnly() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox/nested").mkdirs()
        File(source, "inbox/a.txt").writeText("A")
        File(source, "inbox/nested/b.txt").writeText("BB")
        File(source, "out").mkdirs()
        File(source, "out/result.txt").writeText("任务输出")
        File(source, "note.txt").writeText("inbox 外")

        assertEquals(BranchCopyResult(copied = 2), copyInboxTree(source, target, maxBytes = 1024))

        assertEquals("A", File(target, "inbox/a.txt").readText())
        assertEquals("BB", File(target, "inbox/nested/b.txt").readText())
        assertFalse(File(target, "out").exists())
        assertFalse(File(target, "note.txt").exists())
    }

    @Test fun skipsSymbolicLinks() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        val outside = tmp.newFile("outside.txt").apply { writeText("outside") }
        File(source, "inbox").mkdirs()
        File(source, "inbox/real.txt").writeText("R")
        val link = File(source, "inbox/link.txt").toPath()
        val created = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        assumeTrue("platform cannot create symbolic links", created)

        val result = copyInboxTree(source, target, maxBytes = 1024)!!

        assertEquals(1, result.copied)
        assertTrue(result.skipped >= 1)
        assertFalse(File(target, "inbox/link.txt").exists())
        assertEquals("R", File(target, "inbox/real.txt").readText())
    }

    @Test fun overTheLimitLeavesNoHalfCopiedInbox() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        File(source, "inbox/a.txt").writeBytes(ByteArray(600) { 'a'.code.toByte() })
        File(source, "inbox/b.txt").writeBytes(ByteArray(600) { 'b'.code.toByte() })

        val result = copyInboxTree(source, target, maxBytes = 1000)!!

        assertEquals(BranchCopyResult(copied = 0, truncated = true), result)
        assertFalse(File(target, "inbox").exists())
        assertTrue(target.listFiles().orEmpty().none { it.name.startsWith(".inbox-tmp-") })
    }

    @Test fun missingSourceInboxReturnsNull() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        assertNull(copyInboxTree(source, target, maxBytes = 1024))
        assertNull(copyInboxTree(tmp.newFolder("no-workspace"), target, maxBytes = 1024))
    }

    @Test fun keepsAnExistingTargetInbox() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        File(source, "inbox/a.txt").writeText("新的")
        File(target, "inbox").mkdirs()
        File(target, "inbox/a.txt").writeText("旧的")

        assertEquals(BranchCopyResult(copied = 0, skipped = 1), copyInboxTree(source, target, maxBytes = 1024))
        assertEquals("旧的", File(target, "inbox/a.txt").readText())
    }

    @Test fun createsAnEmptyInboxForAnEmptySourceInbox() {
        val source = tmp.newFolder("source")
        val target = tmp.newFolder("target")
        File(source, "inbox").mkdirs()
        assertEquals(BranchCopyResult(copied = 0), copyInboxTree(source, target, maxBytes = 1024))
        assertTrue(File(target, "inbox").isDirectory)
    }
}
