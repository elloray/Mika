package com.wangzan101.mika.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.SharedDraftCopy
import com.wangzan101.mika.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 文件库的来源；工作区路径必须是 /workspace/ 下的路径。 */
sealed interface LibraryFileHandle {
    data class Workspace(val conversationId: String, val path: String) : LibraryFileHandle
    data class Exported(val file: SharedFile) : LibraryFileHandle
}

enum class LibraryAttachmentResult { Added, Full, Unavailable, Failed }

/** 文件库操作。工作区文件经闸门复制；导出文件预览和分享使用原 MediaStore URI。 */
object LibraryFileActions {
    private val startupLock = Any()
    private var orphanCleanupDone = false

    /** 普通附件草稿不跨进程恢复；本进程首次创建副本前清理上次进程遗留的副本。 */
    private fun cleanupOrphanedDraftCopies(context: Context) = synchronized(startupLock) {
        if (!orphanCleanupDone) {
            File(context.filesDir, "library-attachments").listFiles()?.forEach { it.deleteRecursively() }
            orphanCleanupDone = true
        }
    }

    /** 即时检查，只代表检查时可读；后续操作仍会重新打开来源。 */
    suspend fun isAvailable(context: Context, source: LibraryFileHandle): Boolean = withContext(Dispatchers.IO) {
        try {
            when (source) {
                is LibraryFileHandle.Workspace -> {
                    require(source.conversationId.isNotBlank())
                    val rel = requireNotNull(AttachFiles.relative(source.path))
                    val dirs = SandboxDirs(context)
                    dirs.gate.openRead(dirs.workspace(source.conversationId), rel).use { it.input().read() }
                }
                is LibraryFileHandle.Exported -> {
                    val uri = Uri.parse(source.file.uri)
                    require(uri.scheme == "content" && uri.authority == "media")
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { it.read() }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun preview(context: Context, source: LibraryFileHandle): Boolean =
        show(context, source) { uri, name -> FileViewer.open(context, uri, name) }

    suspend fun share(context: Context, source: LibraryFileHandle): Boolean =
        show(context, source) { uri, name -> FileViewer.share(context, uri, name) }

    /** 先复制来源，再在主线程合并目标草稿；复制期间切换会话不会改变目标。 */
    suspend fun addToDraft(context: Context, viewModel: ChatViewModel, targetConversationId: String,
                           source: LibraryFileHandle): LibraryAttachmentResult {
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val copy = stage(context, source, forDraft = true) ?: return@withContext LibraryAttachmentResult.Failed
            var accepted = false
            try {
                val result = withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    viewModel.addLibraryAttachment(targetConversationId, copy.attachment, source) { copy.file.parentFile?.deleteRecursively() }
                }
                accepted = result == LibraryAttachmentResult.Added
                result
            } finally {
                if (!accepted) copy.file.parentFile?.deleteRecursively()
            }
        }
    }

    private suspend fun show(context: Context, source: LibraryFileHandle, open: (Uri, String) -> Unit): Boolean {
        if (source is LibraryFileHandle.Exported) {
            if (!isAvailable(context, source)) return false
            return try {
                withContext(Dispatchers.Main.immediate) {
                    open(Uri.parse(source.file.uri), source.file.name)
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            val copy = stage(context, source, forDraft = false) ?: return@withContext false
            var opened = false
            try {
                withContext(Dispatchers.Main.immediate) {
                    caller.ensureActive()
                    open(Uri.parse(copy.attachment.uri), copy.attachment.name)
                }
                opened = true
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                if (!opened) copy.file.parentFile?.deleteRecursively()
            }
        }
    }

    private data class Copy(val file: File, val attachment: PendingAttachment)

    private suspend fun stage(context: Context, source: LibraryFileHandle, forDraft: Boolean): Copy? =
        withContext(Dispatchers.IO + NonCancellable) {
            cleanupOrphanedDraftCopies(context)
            val name = when (source) {
                is LibraryFileHandle.Workspace -> source.path.substringAfterLast('/')
                is LibraryFileHandle.Exported -> source.file.name
            }.replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").take(180)
                .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
            val root = if (forDraft) File(context.filesDir, "library-attachments") else File(context.cacheDir, "library-viewing")
            if (!forDraft) {
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
                root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
            }
            val dir = File(root, UUID.randomUUID().toString())
            try {
                check(dir.mkdirs())
                val file = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                when (source) {
                    is LibraryFileHandle.Workspace -> {
                        require(source.conversationId.isNotBlank())
                        val rel = requireNotNull(AttachFiles.relative(source.path))
                        val dirs = SandboxDirs(context)
                        dirs.gate.openRead(dirs.workspace(source.conversationId), rel).use { gated ->
                            SharedDraftCopy.copy(gated.input(), tmp, file) { dir.usableSpace }
                        }
                    }
                    is LibraryFileHandle.Exported -> {
                        val uri = Uri.parse(source.file.uri)
                        require(uri.scheme == "content" && uri.authority == "media")
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            SharedDraftCopy.copy(input, tmp, file) { dir.usableSpace }
                        }
                    }
                }
                dir.setLastModified(System.currentTimeMillis())
                val uri = FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file)
                Copy(file, PendingAttachment(uri.toString(), name, file.length()))
            } catch (_: Exception) {
                dir.deleteRecursively()
                null
            }
        }
}
