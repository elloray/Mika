package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/** 附加进来的一批文件:[added] 是 (沙箱路径, 大小),[rejected] 是没收下的文件名(太大或读不了)。 */
data class ImportResult(val added: List<Pair<String, Long>>, val rejected: List<String>)

/** 用户在聊天里附加的文件:复制进当前对话工作区的 inbox/,沙箱里在 /workspace/inbox/。 */
object Attachments {
    const val MAX_BYTES = 200L shl 20
    const val MIN_FREE = 500L shl 20
    const val WORKSPACE_MAX = 4L shl 30
    private const val CHECK_EVERY = 16L shl 20
    private const val MAX_NAME = 120
    private const val TAG = "MikaAttachments"

    /** 去掉路径分隔符、控制字符和格式字符,保留中文与空格,不超过 120 字;什么都不剩时叫 attachment。 */
    fun safeName(display: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < display.length) {
            val cp = display.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '/'.code || cp == '\\'.code || ShareRules.hiddenChar(cp)) continue
            sb.appendCodePoint(cp)
        }
        val name = sb.toString().trim().trimStart('.')
        // 文件系统按字节算长度:中文名 120 字也可能超过 255 字节;截短时保留扩展名
        return ShareRules.fitName(name.ifEmpty { "attachment" }, maxChars = MAX_NAME)
    }

    /** 已有同名文件时加「 (2)」「 (3)」……,扩展名留在最后。 */
    fun numbered(name: String, n: Int): String = WorkspaceFileGate.numbered(name, n)

    /**
     * 复制到 [workspace] 下的 inbox/;每个文件 ≤ 200 MB(超过的删掉、记进 rejected)。
     * 每个文件开始前(复制中每 16 MB 也)看一次空间:手机可用空间低于 500 MB、或工作区超过 4 GB 就不收。
     * 经 [gate] 在 inbox/ 里新建(不覆盖、按句柄核实):inbox 被沙箱里的命令换成链接也写不出去;失败时只删自己建的那个文件。
     */
    suspend fun importInto(
        context: Context, uris: List<Uri>, workspace: File, gate: WorkspaceFileGate, tree: SandboxTree,
        freeBytes: () -> Long = { workspace.usableSpace }, workspaceBytes: () -> Long = { 0L },
    ): ImportResult = tree.shared { withContext(Dispatchers.IO) { // 写进 files/sandbox/:和重置互斥
        val added = mutableListOf<Pair<String, Long>>()
        val rejected = mutableListOf<String>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val display = displayName(context, uri) ?: uri.lastPathSegment ?: "attachment"
            val base = safeName(display)
            if (!roomLeft(freeBytes, workspaceBytes)) { rejected += base; continue }
            val result = try {
                copyOne(workspace, gate, base, { roomLeft(freeBytes, workspaceBytes) }) {
                    context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open attachment")
                }.use { it.entry() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // 之前这里把异常吞掉、只记名字,上传失败的原因用户和日志都看不到;记下来好定位是哪一步(读不到/超限/空间不足)。
                Log.w(TAG, "Attachment import failed for \"$base\" (${e.javaClass.simpleName}: ${e.message})")
                null
            }
            if (result == null) rejected += base else added += result
        }
        ImportResult(added, rejected)
    } }

    private fun roomLeft(freeBytes: () -> Long, workspaceBytes: () -> Long) =
        freeBytes() >= MIN_FREE && workspaceBytes() <= WORKSPACE_MAX

    /** Keep the exact handles until the phone accepts the task, so a failed batch leaves no partial input. */
    suspend fun <T> withTaskFiles(
        sources: List<Pair<String, File>>, workspace: File, gate: WorkspaceFileGate, tree: SandboxTree,
        room: () -> Boolean, launch: (List<Pair<String, Long>>) -> T,
    ): T = tree.shared {
        val created = mutableListOf<GatedFile>()
        try {
            for ((name, source) in sources) {
                if (!room()) throw IOException("Not enough space for task attachments")
                created += copyOne(workspace, gate, safeName(name), room) { source.inputStream() }
            }
            launch(created.map { it.entry() })
        } catch (e: Throwable) {
            created.forEach { it.delete() }
            throw e
        } finally { created.forEach { it.close() } }
    }

    private suspend fun copyOne(workspace: File, gate: WorkspaceFileGate, base: String, room: () -> Boolean,
        open: () -> InputStream): GatedFile {
        workspace.mkdirs()
        val (_, g) = gate.createNumbered(workspace, INBOX, base, max = 1000)
        var size = 0L
        try {
            open().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val r = input.read(buf)
                    if (r < 0) break
                    size += r
                    if (size > MAX_BYTES || (size % CHECK_EVERY < r && !room())) throw IOException("Attachment storage limit reached")
                    g.write(if (r == buf.size) buf else buf.copyOf(r))
                }
            }
            return g
        } catch (e: Throwable) {
            try { g.delete() } finally { g.close() }
            throw e
        }
    }

    private fun GatedFile.entry() = "${SandboxPaths.WORKSPACE}/$INBOX/$name" to currentSize()

    private const val INBOX = "inbox"

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}
