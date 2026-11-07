package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID

/** 复制分支工作区的结果:[skipped] 是没收下的项(符号链接、越界路径、非常规文件);[truncated] 为 true 时超过上限、一个文件都没复制。 */
data class BranchCopyResult(
    val copied: Int,
    val skipped: Int = 0,
    /** 超过上限、中止复制。 */
    val truncated: Boolean = false,
)

/** 分支时复制源对话的工作区(只要用户附件 inbox/);阶段 2 由对话视图注入。 */
interface BranchWorkspace {
    /**
     * 只复制源对话工作区的 inbox/;目标工作区不存在就建。
     * 源工作区/沙箱不可用、源没有 inbox 时返回 null;目标已有 inbox 时不覆盖,返回 skipped = 1。
     */
    suspend fun copyInbox(
        sourceId: String,
        targetId: String,
        maxBytes: Long = 200L * 1024 * 1024,
    ): BranchCopyResult?
}

/** 手机上的实现:两个工作区都在 files/sandbox/ 里,复制时拿同一把树锁,和重置、任务共用工作区互斥。 */
class AndroidBranchWorkspace(private val dirs: SandboxDirs) : BranchWorkspace {
    constructor(context: Context) : this(SandboxDirs(context))

    override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? =
        dirs.tree.shared {
            withContext(Dispatchers.IO) {
                copyInboxTree(dirs.workspace(sourceId), dirs.workspace(targetId), maxBytes)
            }
        }
}

private const val INBOX = "inbox"

/**
 * 把 [source]/inbox 复制到 [target]/inbox:只收普通文件,不跟随符号链接,路径规范化后必须仍在各自 inbox 下;
 * 先复制到 target/.inbox-tmp-<uuid>,全部成功后再改名。超过 [maxBytes] 就中止、删掉临时目录,返回
 * `BranchCopyResult(copied = 0, truncated = true)`,不留半成品。
 * 源没有 inbox 返回 null;目标已有 inbox 不覆盖,返回 `BranchCopyResult(copied = 0, skipped = 1)`;
 * 复制中出错也返回 null。不拿树锁,手机实现在锁里调它。
 */
internal fun copyInboxTree(source: File, target: File, maxBytes: Long): BranchCopyResult? {
    val sourceInbox = File(source, INBOX)
    if (!sourceInbox.isDirectory || Files.isSymbolicLink(sourceInbox.toPath())) return null
    val sourceRoot = sourceInbox.canonicalFile.toPath()
    if (!target.isDirectory && !target.mkdirs()) return null
    val dest = File(target, INBOX)
    if (dest.exists() || Files.isSymbolicLink(dest.toPath())) return BranchCopyResult(copied = 0, skipped = 1)
    val tmp = File(target, ".inbox-tmp-${UUID.randomUUID()}")
    if (!tmp.mkdirs()) return null
    val tmpRoot = tmp.canonicalFile.toPath()
    var copied = 0
    var skipped = 0
    var bytes = 0L
    var truncated = false

    fun walk(dir: File, rel: Path) {
        val children = dir.listFiles() ?: throw IOException("cannot list: $dir")
        for (child in children.sortedBy { it.name }) {
            if (truncated) return
            if (Files.isSymbolicLink(child.toPath())) {
                skipped++
                continue
            }
            val real = child.canonicalFile.toPath()
            if (!real.startsWith(sourceRoot)) {
                skipped++
                continue
            }
            val dst = tmpRoot.resolve(rel.resolve(child.name)).normalize()
            if (!dst.startsWith(tmpRoot)) {
                skipped++
                continue
            }
            when {
                child.isDirectory -> {
                    mkdirs(dst)
                    walk(child, rel.resolve(child.name))
                }
                child.isFile -> {
                    val written = copyFile(child, dst, maxBytes - bytes)
                    if (written == null) {
                        truncated = true
                        return
                    }
                    bytes += written
                    copied++
                }
                // 管道、套接字等用户附件没有的东西不复制
                else -> skipped++
            }
        }
    }

    try {
        walk(sourceInbox, Paths.get(""))
    } catch (e: Exception) {
        tmp.deleteRecursively()
        return null
    }
    if (truncated) {
        tmp.deleteRecursively()
        return BranchCopyResult(copied = 0, truncated = true)
    }
    if (!tmp.renameTo(dest)) {
        tmp.deleteRecursively()
        return null
    }
    return BranchCopyResult(copied, skipped)
}

/** 复制一个普通文件,最多写 [max] 字节;超了返回 null(临时目录由调用方整个删掉)。 */
private fun copyFile(source: File, dest: Path, max: Long): Long? {
    dest.parent?.let(::mkdirs)
    var total = 0L
    Files.newInputStream(source.toPath()).use { input ->
        Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                output.write(buf, 0, n)
            }
        }
    }
    return total
}

private fun mkdirs(path: Path) {
    val f = path.toFile()
    if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create directory: $path")
}
