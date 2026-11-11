package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** File-only bridge used by screen tools through a callback; it never starts a Linux process. */
internal class WorkspaceScreenshots(private val sandbox: SandboxPort, private val workspace: () -> String) {
    suspend fun save(raw: String, png: ByteArray): String = sandbox.tree.shared { withContext(Dispatchers.IO) {
        val path = SandboxPaths.normalize(raw)?.takeIf { SandboxPaths.isWritable(it) && it != SandboxPaths.WORKSPACE }
            ?: throw SandboxError("path_outside_workspace", "savePath must be under /workspace.", recoverable = true)
        if (!path.endsWith(".png", ignoreCase = true))
            throw SandboxError("invalid_arguments", "savePath must end in .png.", recoverable = true)
        if (png.isEmpty() || png.size > MAX_BYTES)
            throw SandboxError("file_too_large", "The screenshot must be nonempty and at most 20 MB.", recoverable = true)
        val ws = workspace()
        if (sandbox.freeBytes() - png.size < MIN_FREE || sandbox.workspaceBytes(ws) + png.size > WORKSPACE_MAX)
            throw SandboxError("storage_low", "Not enough space to save this screenshot.", recoverable = true)
        val (root, rel) = sandbox.hostRoot(ws, path, forWrite = true)
            ?: throw SandboxError("path_outside_workspace", "savePath must be under /workspace.", recoverable = true)
        currentCoroutineContext().ensureActive()
        // Do not overwrite a result file already selected by the user or another tool.
        sandbox.files.createNew(root, rel).use { file ->
            try {
                file.write(png)
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
        path
    } }

    private companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val MIN_FREE = 500L * 1024 * 1024
        const val WORKSPACE_MAX = 4L * 1024 * 1024 * 1024
    }
}
